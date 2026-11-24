package tn.finrisk.aiservice.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tn.finrisk.aiservice.client.AccountClient;
import tn.finrisk.aiservice.client.AlertClient;
import tn.finrisk.aiservice.client.AlertGroupClient;
import tn.finrisk.aiservice.client.CustomerClient;
import tn.finrisk.aiservice.client.RiskClient;
import tn.finrisk.aiservice.client.TransactionClient;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertGroupView;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.aiservice.service.dto.AnalysisContext;
import tn.finrisk.aiservice.web.dto.AnalysisResponse;
import tn.finrisk.aiservice.web.dto.AiVerdict;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Orchestrateur : assemble le contexte en CHAÎNE, uniquement par API
 * (règle M0), puis laisse les moteurs purs travailler :
 *
 *   alerte -> evaluation (score) -> transaction -> compte -> customer
 *        -> comptes du client -> historique 90j (max 5 comptes)
 *        -> scan global (graph)
 *
 * Deux consommateurs :
 *   - analyze()     : l'endpoint Analyze (étape 3)
 *   - analyzeFull() : le chat (étape 4) a besoin du CONTEXTE BRUT
 *                     (montant, bénéficiaire, pays...) pour répondre
 *                     à toutes les questions, pas seulement du verdict.
 *
 * Aucune décision n'est prise : le résultat est un AVIS pour l'analyste.
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    /** Fenêtre d'historique : la même que le Feature Engine de risk-service. */
    static final int HISTORY_DAYS = 90;

    /** Au plus 5 comptes interrogés par alerte (bornage des appels sortants). */
    static final int MAX_ACCOUNTS = 5;

    /**
     * Balayage global (carburant du graphe) : plusieurs pages, pas une seule.
     * Une page de 100 sur un portefeuille de milliers de transactions ferait
     * croire que le réseau observé est le réseau réel — d'où SCAN_PAGES.
     */
    static final int SCAN_PAGE_SIZE = 100;
    static final int SCAN_PAGES = 3;

    /** Échantillon global réellement chargé + total déclaré par le distant. */
    record GlobalScan(List<TransactionView> transactions, long totalElements) {
    }

    /** Tout ce que Analyze a trouvé : contexte brut + signaux + réponse formatée. */
    public record FullAnalysis(AnalysisContext context, List<Insight> insights,
            AnalysisResponse response) {
    }

    private final AlertClient alertClient;
    private final AlertGroupClient alertGroupClient;
    private final RiskClient riskClient;
    private final TransactionClient transactionClient;
    private final AccountClient accountClient;
    private final CustomerClient customerClient;
    private final InsightEngine insightEngine;

    public AiAnalysisService(AlertClient alertClient, AlertGroupClient alertGroupClient,
            RiskClient riskClient, TransactionClient transactionClient,
            AccountClient accountClient, CustomerClient customerClient,
            InsightEngine insightEngine) {
        this.alertClient = alertClient;
        this.alertGroupClient = alertGroupClient;
        this.riskClient = riskClient;
        this.transactionClient = transactionClient;
        this.accountClient = accountClient;
        this.customerClient = customerClient;
        this.insightEngine = insightEngine;
    }

    public AnalysisResponse analyze(Long alertId) {
        return analyzeFull(alertId).response();
    }

    public FullAnalysis analyzeFull(Long alertId) {
        AlertView alert = alertClient.getAlertById(alertId);
        EvaluationView evaluation = riskClient.getEvaluationById(alert.assessmentId());
        TransactionView transaction = transactionClient.getTransactionById(evaluation.transactionId());
        Long customerId = accountClient.getAccountById(transaction.accountId()).customerId();
        CustomerView customer = customerClient.getCustomerById(customerId);
        List<AccountView> accounts = customerClient.getAccountsOf(customerId);
        GlobalScan globalScan = loadGlobalScan();
        AlertGroupView alertGroup = loadAlertGroup(alert);

        AnalysisContext context = new AnalysisContext(alert, evaluation, transaction, customer,
                accounts, loadHistory(accounts), globalScan.transactions(),
                globalScan.totalElements(), alertGroup);
        List<Insight> insights = insightEngine.analyze(context);

        long high = insights.stream().filter(i -> i.severity() == InsightSeverity.HIGH).count();
        long medium = insights.stream().filter(i -> i.severity() == InsightSeverity.MEDIUM).count();
        boolean insufficient = insights.stream()
                .anyMatch(i -> i.code() == InsightCode.INSUFFICIENT_DATA);

        AiVerdict verdict = AiVerdictEngine.verdict(high, medium);
        double confidence = AiVerdictEngine.confidence(high, medium, insufficient);
        String explanation = AiVerdictEngine.explain(evaluation, insights, verdict, confidence);

        AnalysisResponse response = new AnalysisResponse(alert.alertId(), evaluation.assessmentId(),
                evaluation.riskScore(), evaluation.riskLevel(), evaluation.decision(),
                verdict, confidence, insights, explanation, OffsetDateTime.now(ZoneOffset.UTC));

        log.info("Analyse alerte {} : score={} verdict={} confiance={} insights={}",
                alertId, evaluation.riskScore(), verdict, confidence, insights.size());
        return new FullAnalysis(context, insights, response);
    }

    /**
     * Groupe d'alertes apparentées auquel appartient l'alerte (agrégation M5).
     * DEFENSIF : si alert-case-service est injoignable ou si l'alerte n'a
     * pas de groupe, on retourne null — le graphe n'aura simplement pas
     * de nœud GROUP, l'analyse continue normalement.
     */
    private AlertGroupView loadAlertGroup(AlertView alert) {
        if (alert.groupId() == null) {
            return null;
        }
        try {
            return alertGroupClient.getGroupById(alert.groupId());
        } catch (RemoteResourceNotFoundException e) {
            log.warn("Alerte {} : groupe {} introuvable ({})",
                    alert.alertId(), alert.groupId(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Alerte {} : groupe {} non chargable ({})",
                    alert.alertId(), alert.groupId(), e.getMessage());
            return null;
        }
    }

    /** Historique 90j des comptes du client, dédupliqué par transactionId. */
    private List<TransactionView> loadHistory(List<AccountView> accounts) {
        LocalDate since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(HISTORY_DAYS).toLocalDate();
        Map<Long, TransactionView> dedup = new LinkedHashMap<>();
        accounts.stream().limit(MAX_ACCOUNTS).forEach(account ->
                transactionClient.list(account.accountId(), since, 0, 100)
                        .contentOrEmpty()
                        .forEach(t -> dedup.putIfAbsent(t.transactionId(), t)));
        return new ArrayList<>(dedup.values());
    }

    /**
     * Échantillon global (toutes entités) : carburant du graphe.
     * On remonte jusqu'à SCAN_PAGES pages et on arrête dès qu'une page revient
     * non pleine. totalElements est celui déclaré par le service distant :
     * c'est lui qui dit si l'échantillon est complet ou tronqué — sans ce
     * chiffre, 100 transactions sur 10 000 se feraient passer pour le réseau
     * réel.
     */
    private GlobalScan loadGlobalScan() {
        LocalDate since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(HISTORY_DAYS).toLocalDate();
        Map<Long, TransactionView> dedup = new LinkedHashMap<>();
        long total = 0;
        for (int page = 0; page < SCAN_PAGES; page++) {
            var result = transactionClient.list(null, since, page, SCAN_PAGE_SIZE);
            total = result.totalElements();
            List<TransactionView> content = result.contentOrEmpty();
            content.forEach(t -> {
                if (t.transactionId() != null) {
                    dedup.putIfAbsent(t.transactionId(), t);
                }
            });
            if (content.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return new GlobalScan(new ArrayList<>(dedup.values()), Math.max(total, dedup.size()));
    }
}
