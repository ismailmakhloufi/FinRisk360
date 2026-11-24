package tn.finrisk.riskservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tn.finrisk.riskservice.client.AccountClient;
import tn.finrisk.riskservice.client.MerchantClient;
import tn.finrisk.riskservice.client.TransactionClient;
import tn.finrisk.riskservice.client.dto.AccountView;
import tn.finrisk.riskservice.client.dto.MerchantView;
import tn.finrisk.riskservice.client.dto.TransactionView;
import tn.finrisk.riskservice.domain.RiskAssessment;
import tn.finrisk.riskservice.domain.RiskFactor;
import tn.finrisk.riskservice.domain.RiskProfile;
import tn.finrisk.riskservice.domain.RiskRule;
import tn.finrisk.riskservice.domain.enums.RiskLevel;
import tn.finrisk.riskservice.engine.DecisionEngine;
import tn.finrisk.riskservice.engine.FeatureEngine;
import tn.finrisk.riskservice.engine.RuleEngine;
import tn.finrisk.riskservice.engine.RuleEvaluation;
import tn.finrisk.riskservice.engine.ScoringEngine;
import tn.finrisk.riskservice.engine.TransactionFeatures;
import tn.finrisk.riskservice.repository.RiskAssessmentRepository;
import tn.finrisk.riskservice.repository.RiskFactorRepository;
import tn.finrisk.riskservice.repository.RiskProfileRepository;
import tn.finrisk.riskservice.repository.RiskRuleRepository;
import tn.finrisk.riskservice.web.dto.AssessmentResponse;
import tn.finrisk.riskservice.web.dto.FactorResponse;
import tn.finrisk.riskservice.web.dto.RuleResponse;
import tn.finrisk.riskservice.web.dto.RuleUpdateRequest;
import tn.finrisk.riskservice.web.error.AssessmentNotFoundException;
import tn.finrisk.riskservice.web.error.RuleNotFoundException;

/**
 * Orchestration du pipeline M3 (l'APPELANT declenche, cf. choix d'architecture) :
 *
 *   transaction-service ──┐
 *   account-service ──────┼─> FeatureEngine ─> RuleEngine ─> ScoringEngine ─> DecisionEngine
 *   merchant (8083) ──────┘                                                        │
 *                                                                                  v
 *                 risk_assessments + risk_factors + risk_profiles (upsert) + reponse
 *
 * Choix structurants :
 *  - historique au niveau CLIENT (tous les comptes) ;
 *  - profil RECALCULE a chaque evaluation (le doc : "recalcule a chaque transaction") ;
 *  - risk_assessments est append-only : chaque evaluation laisse une trace.
 */
@Service
@Transactional(readOnly = true)
public class EvaluationService {

    private final TransactionClient transactionClient;
    private final AccountClient accountClient;
    private final MerchantClient merchantClient;
    private final RiskRuleRepository ruleRepository;
    private final RiskAssessmentRepository assessmentRepository;
    private final RiskFactorRepository factorRepository;
    private final RiskProfileRepository profileRepository;
    private final FeatureEngine featureEngine;
    private final RuleEngine ruleEngine;
    private final ScoringEngine scoringEngine;
    private final DecisionEngine decisionEngine;
    private final ObjectMapper objectMapper;
    private final int statsWindowDays;
    private final String engineVersion;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public EvaluationService(TransactionClient transactionClient,
                             AccountClient accountClient,
                             MerchantClient merchantClient,
                             RiskRuleRepository ruleRepository,
                             RiskAssessmentRepository assessmentRepository,
                             RiskFactorRepository factorRepository,
                             RiskProfileRepository profileRepository,
                             FeatureEngine featureEngine,
                             RuleEngine ruleEngine,
                             ScoringEngine scoringEngine,
                             DecisionEngine decisionEngine,
                             ObjectMapper objectMapper,
                             @Value("${risk.stats-window-days:90}") int statsWindowDays,
                             @Value("${risk.engine-version:v1}") String engineVersion,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.transactionClient = transactionClient;
        this.accountClient = accountClient;
        this.merchantClient = merchantClient;
        this.ruleRepository = ruleRepository;
        this.assessmentRepository = assessmentRepository;
        this.factorRepository = factorRepository;
        this.profileRepository = profileRepository;
        this.featureEngine = featureEngine;
        this.ruleEngine = ruleEngine;
        this.scoringEngine = scoringEngine;
        this.decisionEngine = decisionEngine;
        this.objectMapper = objectMapper;
        this.statsWindowDays = statsWindowDays;
        this.engineVersion = engineVersion;
        this.jdbc = jdbc;
    }

    /**
     * DELETE /api/evaluations?customerId= : purge les assessments d'un client.
     * risk_factors part en CASCADE (fk_rf_assessment) ; les alertes associees
     * ont deja ete purgees chez alert-case-service (ordre impose par l'appelant).
     * La sous-requete ne fait que LIRE transactions/accounts (ecriture exclusive
     * de chaque service sur ses propres tables).
     */
    @Transactional
    public int deleteByCustomer(Long customerId) {
        return jdbc.update(
                "DELETE FROM risk_assessments WHERE transaction_id IN "
                        + "(SELECT t.transaction_id FROM transactions t "
                        + " JOIN accounts ac ON ac.account_id = t.account_id "
                        + " WHERE ac.customer_id = ?)",
                customerId);
    }

    /** POST /api/evaluations : le pipeline complet, en une seule transaction SQL. */
    @Transactional
    public AssessmentResponse evaluate(Long transactionId) {
        // 1. Donnees : jamais de SQL chez les autres, uniquement leurs API.
        TransactionView transaction = transactionClient.getById(transactionId);
        AccountView account = accountClient.getById(transaction.accountId());
        Long customerId = account.customerId();

        List<AccountView> accounts = accountClient.listByCustomer(customerId);
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(statsWindowDays);
        List<TransactionView> history = new ArrayList<>();
        for (AccountView customerAccount : accounts) {
            history.addAll(transactionClient.listByAccountSince(
                    customerAccount.accountId(), since));
        }

        Integer merchantAgeDays = null;
        if (transaction.merchantId() != null) {
            MerchantView merchant = merchantClient.getById(transaction.merchantId());
            if (merchant.createdAt() != null) {
                merchantAgeDays = (int) Math.max(0, ChronoUnit.DAYS.between(
                        merchant.createdAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate(),
                        LocalDate.now(ZoneOffset.UTC)));
            }
        }

        // 2. Le moteur.
        TransactionFeatures features =
                featureEngine.compute(transaction, customerId, history, merchantAgeDays);
        List<RiskRule> rules = ruleRepository.findByEnabledTrueOrderByRuleCode();
        List<RuleEvaluation> evaluations = ruleEngine.evaluate(features, rules);
        BigDecimal score = scoringEngine.score(evaluations);
        DecisionEngine.Outcome outcome = decisionEngine.decide(score);

        // 3. Persistance : verdict, facteurs explicatifs, profil rafraichi.
        RiskAssessment assessment = new RiskAssessment();
        assessment.setTransactionId(transactionId);
        assessment.setRiskScore(score);
        assessment.setRiskLevel(outcome.level());
        assessment.setDecision(outcome.decision());
        assessment.setEngineVersion(engineVersion);
        assessmentRepository.save(assessment);

        List<FactorResponse> factorResponses = new ArrayList<>();
        for (RuleEvaluation evaluation : evaluations) {
            if (!evaluation.worthPersisting()) {
                continue;
            }
            RiskFactor factor = new RiskFactor();
            factor.setAssessmentId(assessment.getAssessmentId());
            factor.setRuleId(evaluation.rule().getRuleId());
            factor.setFactorType(evaluation.rule().getRiskCategory());
            factor.setFactorScore(evaluation.factor());
            factor.setWeight(evaluation.rule().getWeight());
            factor.setReason(evaluation.reason());
            factorRepository.save(factor);
            factorResponses.add(new FactorResponse(
                    factor.getRuleId(),
                    evaluation.rule().getRuleCode(),
                    evaluation.rule().getRuleName(),
                    factor.getFactorType().name(),
                    factor.getFactorScore(),
                    factor.getWeight(),
                    factor.getReason()));
        }

        upsertProfile(features, customerId, outcome.level());

        // On NE TOUCHE PAS au statut de la transaction : toute operation est
        // soumise a un examen humain (validation manuelle obligatoire). Le
        // verdict alimente l'ALERTE ouverte par le controller, c'est l'analyste
        // qui decide de passer en COMPLETED (regulation du solde) ou d'annuler
        // (aucun solde n'a bouge tant que la transaction etait PENDING).
        // Un statut fige ici (COMPLETED est TERMINAL) rendrait l'annulation
        // impossible et echapperait donc a tout controle.

        return new AssessmentResponse(
                assessment.getAssessmentId(), transactionId, score,
                outcome.level(), outcome.decision(), assessment.getAssessedAt(),
                assessment.getEngineVersion(), factorResponses);
    }

    /** GET /api/evaluations/{id} */
    @Transactional(readOnly = true)
    public AssessmentResponse getAssessment(Long assessmentId) {
        RiskAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new AssessmentNotFoundException("assessmentId", assessmentId));
        return toResponse(assessment);
    }

    /** GET /api/evaluations/transaction/{transactionId} : le verdict le plus recent. */
    @Transactional(readOnly = true)
    public AssessmentResponse getLatestForTransaction(Long transactionId) {
        List<RiskAssessment> history =
                assessmentRepository.findByTransactionIdOrderByAssessedAtDesc(transactionId);
        if (history.isEmpty()) {
            throw new AssessmentNotFoundException("transactionId", transactionId);
        }
        return toResponse(history.getFirst());
    }

    /** GET /api/rules : tout le catalogue, actives ou non. */
    @Transactional(readOnly = true)
    public List<RuleResponse> listRules() {
        return ruleRepository.findAll().stream()
                .sorted((a, b) -> a.getRuleCode().compareTo(b.getRuleCode()))
                .map(this::toRuleResponse)
                .toList();
    }

    /** PUT /api/rules/{id} : mise a jour partielle (demos : bascule enabled/weight). */
    @Transactional
    public RuleResponse updateRule(Long ruleId, RuleUpdateRequest request) {
        RiskRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        if (request.weight() != null) {
            rule.setWeight(request.weight());
        }
        if (request.thresholdValue() != null) {
            rule.setThresholdValue(request.thresholdValue());
        }
        return toRuleResponse(ruleRepository.save(rule));
    }

    // --------------------------------------------------------------- PRIVE

    /**
     * Profil = vue materialisee : recalcule et ecrit a chaque evaluation.
     * Pas de @Transactional ici : on JOINTE la transaction de evaluate()
     * (appel intra-classe = le proxy ne serait de toute facon pas passe).
     */
    protected void upsertProfile(TransactionFeatures features, Long customerId, RiskLevel level) {
        RiskProfile profile = profileRepository.findByCustomerId(customerId)
                .orElseGet(RiskProfile::new);
        profile.setCustomerId(customerId);
        if (features.hasBaseline()) {
            TransactionFeatures.HistoryStats stats = features.stats();
            profile.setAvgTransactionAmount(stats.avg());
            profile.setMedianTransactionAmount(stats.median());
            profile.setMaxTransactionAmount(stats.max());
            profile.setP95Amount(stats.p95());
            profile.setP99Amount(stats.p99());
            profile.setTransactionsPerDay(stats.transactionsPerDay());
            profile.setUsualCountry(stats.usualCountry());
            profile.setUsualTransactionHour(stats.usualHour());
            profile.setUsualMerchantCount(stats.usualMerchantCount());
        }
        profile.setStatsWindowDays(statsWindowDays);
        profile.setProfileRiskLevel(level);
        profile.setFeatureSnapshot(snapshotJson(features));
        profileRepository.save(profile);
    }

    /** Snapshot JSONB des features (explicabilite, carburant M6 IA). Best effort. */
    private String snapshotJson(TransactionFeatures features) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("engineVersion", engineVersion);
            snapshot.put("localHour", features.localHour());
            snapshot.put("tnd", features.tnd());
            snapshot.put("merchantAgeDays", features.merchantAgeDays());
            if (features.hasBaseline()) {
                TransactionFeatures.HistoryStats stats = features.stats();
                snapshot.put("historySize", stats.size());
                snapshot.put("avg", stats.avg());
                snapshot.put("sigma", stats.sigma());
                snapshot.put("median", stats.median());
                snapshot.put("p95", stats.p95());
                snapshot.put("p99", stats.p99());
                snapshot.put("transactionsPerDay", stats.transactionsPerDay());
                snapshot.put("usualCountry", stats.usualCountry());
                snapshot.put("usualHour", stats.usualHour());
                snapshot.put("usualMerchantCount", stats.usualMerchantCount());
            }
            TransactionFeatures.ActivityStats activity = features.activity();
            snapshot.put("txCount24h", activity.txCount24h());
            snapshot.put("cumul24hTnd", activity.cumul24hTnd());
            snapshot.put("failedCount24h", activity.failedCount24h());
            snapshot.put("smallTxCount24h", activity.smallTxCount24h());
            snapshot.put("smallTxSum24h", activity.smallTxSum24h());
            snapshot.put("minGapCountryMinutes", activity.minGapCountryMinutes());
            snapshot.put("minGapDuplicateMinutes", activity.minGapDuplicateMinutes());
            snapshot.put("newMerchantForCustomer", activity.newMerchantForCustomer());
            return objectMapper.writeValueAsString(snapshot);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private AssessmentResponse toResponse(RiskAssessment assessment) {
        List<RiskFactor> persisted = factorRepository
                .findByAssessmentId(assessment.getAssessmentId());

        // Un seul aller-retour pour resoudre tous les codes de regles (pas de N+1).
        List<Long> ruleIds = persisted.stream()
                .map(RiskFactor::getRuleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, RiskRule> rulesById = new LinkedHashMap<>();
        ruleRepository.findAllById(ruleIds).forEach(r -> rulesById.put(r.getRuleId(), r));

        List<FactorResponse> factors = persisted.stream()
                .map(factor -> {
                    RiskRule rule = rulesById.get(factor.getRuleId());
                    return new FactorResponse(
                            factor.getRuleId(),
                            rule == null ? null : rule.getRuleCode(),
                            rule == null ? null : rule.getRuleName(),
                            factor.getFactorType().name(),
                            factor.getFactorScore(),
                            factor.getWeight(),
                            factor.getReason());
                })
                .toList();

        return new AssessmentResponse(
                assessment.getAssessmentId(), assessment.getTransactionId(),
                assessment.getRiskScore(), assessment.getRiskLevel(), assessment.getDecision(),
                assessment.getAssessedAt(), assessment.getEngineVersion(), factors);
    }

    private RuleResponse toRuleResponse(RiskRule rule) {
        return new RuleResponse(
                rule.getRuleId(),
                rule.getRuleCode(),
                rule.getRuleName(),
                rule.getRiskCategory(),
                rule.getComparisonOperator() == null ? null : rule.getComparisonOperator().getSymbol(),
                rule.getThresholdValue(),
                rule.getWeight(),
                rule.getSeverity(),
                rule.getEnabled());
    }
}
