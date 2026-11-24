package tn.finrisk.alertcaseservice.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;

/**
 * Reponse rendue pour une decision de l'AGENT NOCTURNE — le rapport du
 * matin : verdict moteur ET avis IA cote a cote, action, motif.
 */
public record NightAgentResponse(
        Long decisionId,
        Long transactionId,
        Long assessmentId,
        Long alertId,
        BigDecimal amount,
        BigDecimal riskScore,
        String riskLevel,
        String engineDecision,
        String aiVerdict,
        Double aiConfidence,
        String agentAction,
        String agentReason,
        OffsetDateTime runAt) {

    public static NightAgentResponse from(NightAgentDecision d) {
        return new NightAgentResponse(
                d.getDecisionId(), d.getTransactionId(), d.getAssessmentId(), d.getAlertId(),
                d.getAmount(), d.getRiskScore(), d.getRiskLevel(), d.getEngineDecision(),
                d.getAiVerdict(), d.getAiConfidence(),
                d.getAgentAction() == null ? null : d.getAgentAction().name(),
                d.getAgentReason(), d.getRunAt());
    }
}
