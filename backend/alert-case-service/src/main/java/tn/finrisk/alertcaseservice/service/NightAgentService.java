package tn.finrisk.alertcaseservice.service;

import java.time.LocalTime;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tn.finrisk.alertcaseservice.web.error.ConflictException;
import tn.finrisk.alertcaseservice.client.AiClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.PageView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.repository.NightAgentDecisionRepository;

/**
 * AGENT NOCTURNE : l'analyste n'est pas disponible la nuit — un job
 * planifie (chaque heure, fenetre 17h00->08h00) balaie la file EN ATTENTE
 * et applique la matrice hybride (moteur + IA) de NightAgentDecisionRule.
 *
 * Garde-fous :
 *   - fenetre horaire + enable/disable dans application.yml (night-agent.*) ;
 *   - l'agent n'annule JAMAIS (seule action = VALIDATED) ;
 *   - une erreur (service distant en panne, tx sans evaluation) = la tx
 *     reste en attente, l'agent passe a la suivante (une panne ne tue pas
 *     le balayage) ;
 *   - TOUT est ecrit : night_agent_decisions (rapport du matin) + audit_logs.
 *
 * POST /api/night-agent/run : declencheur manuel soumis a la meme fenetre
 * et a la meme activation que le tick planifie.
 */
@Service
public class NightAgentService {

    private static final Logger log = LoggerFactory.getLogger(NightAgentService.class);

    /** Taille de page du parcours (le distant plafonne lui-meme la page). */
    private static final int SCAN_PAGE_SIZE = 100;

    private Clock clock = Clock.system(java.time.ZoneId.of("Africa/Tunis"));

    private final TransactionClient transactionClient;
    private final RiskClient riskClient;
    private final AiClient aiClient;
    private final AlertRepository alertRepository;
    private final NightAgentWorker worker;
    private final NightAgentDecisionRepository repository;

    private final NightAgentIntentStore intents;

    private final boolean enabled;
    private final int startHour;
    private final int endHour;
    private final int maxTransactions;

    /** Desactive par defaut tant que la reprise des mouvements n'est pas fiable. */
    @Value("${night-agent.auto-validation-enabled:false}")
    private boolean autoValidationEnabled;

    public NightAgentService(TransactionClient transactionClient, RiskClient riskClient,
            AiClient aiClient, AlertRepository alertRepository, NightAgentWorker worker,
            NightAgentDecisionRepository repository, NightAgentIntentStore intents,
            @Value("${night-agent.enabled:true}") boolean enabled,
            @Value("${night-agent.start-hour:17}") int startHour,
            @Value("${night-agent.end-hour:8}") int endHour,
            @Value("${night-agent.max-transactions:50}") int maxTransactions) {
        this.transactionClient = transactionClient;
        this.riskClient = riskClient;
        this.aiClient = aiClient;
        this.alertRepository = alertRepository;
        this.worker = worker;
        this.repository = repository;
        this.intents = intents;
        this.enabled = enabled;
        this.startHour = startHour;
        this.endHour = endHour;
        this.maxTransactions = maxTransactions;
    }

    /** Resultat d'un balayage — rendu au declencheur manuel et journalise. */
    public record RunSummary(int scanned, int validated, int deferred, int skipped,
            OffsetDateTime runAt) {
    }

    /** GET /api/night-agent/status — affiche par la page « Rapport de nuit ». */
    public record Status(boolean enabled, int startHour, int endHour, boolean inWindowNow,
            OffsetDateTime lastRunAt, boolean autoValidationEnabled) {
    }

    /** Tick horaire : n'agit que dans la fenetre si l'agent est active. */
    @Scheduled(cron = "0 0 * * * *", zone = "Africa/Tunis")
    public void hourlyTick() {
        if (!enabled || !inWindow(LocalTime.now(clock))) {
            return;
        }
        try {
            RunSummary summary = run();
            log.info("Agent nocturne (fenetre) : {}", summary);
        } catch (Exception e) {
            log.warn("Agent nocturne : balayage interrompu ({})", e.getMessage());
        }
    }

    /** Le declenchement manuel ne doit pas contourner l'horaire de l'analyste. */
    public RunSummary runManual() {
        if (!enabled || !inWindow(LocalTime.now(clock))) {
            throw new ConflictException("Agent nocturne desactive ou hors fenetre de fonctionnement");
        }
        return run();
    }

    /** Balayage de la file EN ATTENTE (manuel ou tick).
     *
     *  La file est PARCOURUE PAGE PAR PAGE (le distant plafonne la page a
     *  100) : le budget night-agent.max-transactions compte les
     *  transactions JUGEES (evaluation + alerte -> matrice), pas celles
     *  simplement vues. Une tx sans alerte est sautee SANS consommer le
     *  budget — sinon, une file remplie de txs sans alerte (le cas du jeu
     *  de demonstration) bloquerait a jamais l'acces a celles qui ont une
     *  alerte, la rangee derriere ne serait jamais examinee.
     *  Une tx deja presente dans night_agent_decisions n'est pas non plus
     *  re-jugee (UNE ligne par tx dans le rapport).
     *  Garde-fou dur : scan plafonne a 10x le budget (500 par defaut). */
    public RunSummary run() {
        if (autoValidationEnabled && enabled && inWindow(LocalTime.now(clock))) {
            for (NightAgentIntentStore.Intent intent : intents.prepared(maxTransactions)) {
                if (!inWindow(LocalTime.now(clock))) {
                    break;
                }
                try {
                    worker.recover(intent);
                } catch (RuntimeException failure) {
                    log.warn("Agent nocturne : reprise de la transaction {} en attente ({})",
                            intent.transactionId(), failure.getMessage());
                }
            }
        }
        int scanned = 0;
        int validated = 0;
        int deferred = 0;
        int skipped = 0;
        int judged = 0;
        final int scanCap = Math.max(maxTransactions * 10, SCAN_PAGE_SIZE);
        int pageIndex = 0;
        boolean more = true;
        while (more && scanned < scanCap && judged < maxTransactions) {
            PageView<TransactionView> pending =
                    transactionClient.listByStatus("PENDING", pageIndex, SCAN_PAGE_SIZE);
            List<TransactionView> content = pending.contentOrEmpty();
            for (TransactionView tx : content) {
                if (judged >= maxTransactions || scanned >= scanCap) {
                    break;
                }
                scanned++;
                try {
                    if (repository.existsByTransactionId(tx.transactionId())
                            || (autoValidationEnabled && intents.exists(tx.transactionId()))) {
                        // Deja au rapport : une ligne par tx, jamais de
                        // re-emission a chaque tick (le bruit tue le rapport).
                        skipped++;
                        continue;
                    }
                    Judgment judgment = judge(tx);
                    if (judgment == null) {
                        skipped++; // rien a juger : sans budget consomme
                        continue;
                    }
                    // Un appel IA peut finir apres 08h : ne pas declencher
                    // de validation une fois la fenetre fermee.
                    if (judgment.outcome().action() == AgentAction.VALIDATED
                            && (!enabled || !inWindow(LocalTime.now(clock)))) {
                        skipped++;
                        continue;
                    }
                    judged++;
                    worker.apply(tx, judgment.eval(), judgment.alert(),
                            judgment.ai(), judgment.outcome());
                    if (judgment.outcome().action() == AgentAction.VALIDATED) {
                        validated++;
                    } else {
                        deferred++;
                    }
                } catch (Exception e) {
                    // Defensif : une tx ne doit jamais tuer le balayage des autres.
                    skipped++;
                    log.warn("Agent nocturne : tx {} sautee ({})", tx.transactionId(), e.getMessage());
                }
            }
            more = content.size() == SCAN_PAGE_SIZE
                    && (long) (pageIndex + 1) * SCAN_PAGE_SIZE < pending.totalElements();
            pageIndex++;
        }
        return new RunSummary(scanned, validated, deferred, skipped,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** Ce que la matrice a produit pour une tx (null = rien a juger). */
    private record Judgment(EvaluationView eval, Alert alert, AiAnalysisView ai,
            NightAgentDecisionRule.Outcome outcome) {
    }

    /**
     * Une transaction : evaluation -> alerte -> avis IA -> matrice.
     * @return le jugement, ou null si la tx a ete SAUTEE (pas
     *         evaluee, pas d'alerte : rien a juger ni a cloturer)
     */
    private Judgment judge(TransactionView tx) {
        EvaluationView eval = riskClient.getEvaluationByTransaction(tx.transactionId());
        if (eval == null) {
            return null; // jamais evaluee : rien a juger
        }
        Optional<Alert> alert = alertRepository.findByAssessmentId(eval.assessmentId());
        if (alert.isEmpty()) {
            return null; // pas d'alerte : l'IA analyse par alerte, rien a faire
        }
        AiAnalysisView ai = null;
        try {
            ai = aiClient.analyze(alert.get().getAlertId());
        } catch (Exception e) {
            // IA injoignable : la matrice tombe sur « IA indisponible » -> LAISSE.
            log.warn("Agent nocturne : IA indisponible pour l'alerte {} ({})",
                    alert.get().getAlertId(), e.getMessage());
        }
        NightAgentDecisionRule.Outcome outcome = NightAgentDecisionRule.decide(eval.riskLevel(),
                ai == null ? null : ai.verdict(), ai == null ? null : ai.confidence());
        if (!autoValidationEnabled && outcome.action() == AgentAction.VALIDATED) {
            outcome = new NightAgentDecisionRule.Outcome(AgentAction.LEFT_PENDING,
                    "Validation automatique suspendue : risque " + eval.riskLevel()
                            + " et IA NORMAL ; verification humaine requise.");
        }
        return new Judgment(eval, alert.get(), ai, outcome);
    }

    /** La fenetre couvre-t-elle maintenant ? (ex. 17 -> 8 : 17h..7h59). */
    boolean inWindow(LocalTime now) {
        int h = now.getHour();
        if (startHour == endHour) {
            return false; // fenetre vide : desactivee par config
        }
        return startHour < endHour
                ? (h >= startHour && h < endHour)
                : (h >= startHour || h < endHour);
    }

    /** GET /api/night-agent/decisions — le rapport du matin (recents d'abord). */
    public org.springframework.data.domain.Page<NightAgentDecision> report(
            org.springframework.data.domain.Pageable pageable) {
        return repository.findByOrderByRunAtDescDecisionIdDesc(pageable);
    }

    /** GET /api/night-agent/status */
    public Status status() {
        OffsetDateTime lastRun = repository.findTopByOrderByRunAtDescDecisionIdDesc()
                .map(NightAgentDecision::getRunAt)
                .orElse(null);
        return new Status(enabled, startHour, endHour,
                enabled && inWindow(LocalTime.now(clock)), lastRun,
                autoValidationEnabled);
    }

    /** Heure de fenetre courante pour les tests unitaires (sans Horloge). */
    boolean isEnabled() {
        return enabled;
    }

    void setClockForTest(Clock clock) {
        this.clock = clock;
    }
}
