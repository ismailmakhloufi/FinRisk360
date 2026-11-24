package tn.finrisk.alertcaseservice.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.repository.NightAgentDecisionRepository;
import tn.finrisk.alertcaseservice.service.NightAgentDecisionRule.Outcome;

/**
 * Persiste l'intention avant l'appel financier. Le reglement ecrit sa preuve
 * dans la meme transaction que le statut COMPLETED. La finalisation locale
 * publie ensuite le rapport, l'audit et la cloture d'alerte ensemble.
 */
@Service
public class NightAgentWorker {

    private static final Logger log = LoggerFactory.getLogger(NightAgentWorker.class);

    static final String AUDIT_VALIDATED = "NIGHT_AGENT_VALIDATED";
    static final String AUDIT_DEFERRED = "NIGHT_AGENT_DEFERRED";

    /** Echelle de cloture legale d'une alerte (meme que le frontend). */
    private static final AlertStatus[] LADDER = {
        AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED, AlertStatus.IN_PROGRESS, AlertStatus.RESOLVED };

    private final TransactionClient transactionClient;
    private final AlertService alertService;
    private final NightAgentDecisionRepository repository;
    private final AuditService auditService;
    private final NightAgentIntentStore intents;

    @Value("${night-agent.auto-validation-enabled:false}")
    private boolean autoValidationEnabled;

    public NightAgentWorker(TransactionClient transactionClient, AlertService alertService,
            NightAgentDecisionRepository repository, AuditService auditService,
            NightAgentIntentStore intents) {
        this.transactionClient = transactionClient;
        this.alertService = alertService;
        this.repository = repository;
        this.auditService = auditService;
        this.intents = intents;
    }

    @Transactional
    public NightAgentDecision apply(TransactionView tx, EvaluationView eval, Alert alert,
            AiAnalysisView ai, Outcome outcome) {

        if (outcome.action() == AgentAction.VALIDATED) {
            if (!autoValidationEnabled) {
                throw new IllegalStateException("Validation automatique suspendue");
            }
            NightAgentIntentStore.Intent intent = intents.prepare(new NightAgentIntentStore.Intent(
                    tx.transactionId(), java.util.UUID.randomUUID(), eval.assessmentId(), alert.getAlertId(),
                    tx.amount(), eval.riskScore(), eval.riskLevel(), eval.decision(), ai.verdict(),
                    ai.confidence(), outcome.reason(), "PREPARED"));
            return completeAndRecord(intent, alert);
        }

        NightAgentDecision row = new NightAgentDecision();
        row.setTransactionId(tx.transactionId());
        row.setAssessmentId(eval.assessmentId());
        row.setAlertId(alert.getAlertId());
        row.setAmount(tx.amount());
        row.setRiskScore(eval.riskScore());
        row.setRiskLevel(eval.riskLevel());
        row.setEngineDecision(eval.decision());
        row.setAiVerdict(ai == null ? null : ai.verdict());
        row.setAiConfidence(ai == null ? null : ai.confidence());
        row.setAgentAction(outcome.action());
        row.setAgentReason(outcome.reason());
        row.setRunAt(OffsetDateTime.now(ZoneOffset.UTC));
        return record(row);
    }

    @Transactional
    public void recover(NightAgentIntentStore.Intent intent) {
        intents.touch(intent);
        completeAndRecord(intent, null);
    }

    private NightAgentDecision completeAndRecord(NightAgentIntentStore.Intent intent, Alert alert) {
        if (!autoValidationEnabled) {
            throw new IllegalStateException("Validation automatique suspendue");
        }
        if (!intents.completed(intent)) {
            try {
                transactionClient.completeNightIntent(intent.transactionId(), intent.intentId());
            } catch (RuntimeException failure) {
                if (!intents.completed(intent)) {
                    throw failure;
                }
                log.info("Validation nocturne {} confirmee par le journal", intent.transactionId());
            }
        }
        if (!intents.finalizeCompleted(intent)) {
            return null; // Une autre execution a deja verse cette decision au rapport.
        }
        closeAlertChain(alert == null ? alertService.get(intent.alertId()) : alert);

        NightAgentDecision row = new NightAgentDecision();
        row.setTransactionId(intent.transactionId());
        row.setAssessmentId(intent.assessmentId());
        row.setAlertId(intent.alertId());
        row.setAmount(intent.amount());
        row.setRiskScore(intent.riskScore());
        row.setRiskLevel(intent.riskLevel());
        row.setEngineDecision(intent.engineDecision());
        row.setAiVerdict(intent.aiVerdict());
        row.setAiConfidence(intent.aiConfidence());
        row.setAgentReason(intent.agentReason());
        row.setAgentAction(AgentAction.VALIDATED);
        row.setRunAt(OffsetDateTime.now(ZoneOffset.UTC));
        return record(row);
    }

    private NightAgentDecision record(NightAgentDecision row) {
        NightAgentDecision saved = repository.save(row);
        auditService.record(null,
                row.getAgentAction() == AgentAction.VALIDATED ? AUDIT_VALIDATED : AUDIT_DEFERRED,
                "TRANSACTION", row.getTransactionId(),
                "PENDING", row.getAgentAction().name(), null);
        return saved;
    }

    /**
     * Cloture de l'alerte par la chaine legale (OPEN -> ACKNOWLEDGED ->
     * IN_PROGRESS -> RESOLVED), une transition par appel. Une alerte deja
     * terminee (dont ABSORBED, geree par son groupe) est ignoree.
     */
    private void closeAlertChain(Alert alert) {
        AlertStatus current = alert.getStatus();
        for (AlertStatus target : LADDER) {
            if (current == AlertStatus.ABSORBED || current.isTerminal()) {
                return;
            }
            if (current.canMoveTo(target)) {
                alert = alertService.changeStatus(alert.getAlertId(), target, null, null);
                current = alert.getStatus();
            }
        }
    }
}
