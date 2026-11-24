package tn.finrisk.riskservice.engine;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskLevel;

/**
 * ④ DECISION ENGINE : score -> niveau + decision.
 *
 * Les bandes viennent de application.yml (risk.decisions.*) : elles sont
 * ajustables sans recompilation. Correspondance naturelle et immuable :
 *   APPROVED -> LOW | MONITORING -> MEDIUM | PENDING_REVIEW -> HIGH | HOLD -> CRITICAL
 */
@Component
public class DecisionEngine {

    private final BigDecimal approvedMax;
    private final BigDecimal monitoringMax;
    private final BigDecimal reviewMax;

    public DecisionEngine(@Value("${risk.decisions.approved-max:29}") int approvedMax,
                          @Value("${risk.decisions.monitoring-max:59}") int monitoringMax,
                          @Value("${risk.decisions.review-max:84}") int reviewMax) {
        this.approvedMax = BigDecimal.valueOf(approvedMax);
        this.monitoringMax = BigDecimal.valueOf(monitoringMax);
        this.reviewMax = BigDecimal.valueOf(reviewMax);
    }

    /** Score 0..100 -> la paire (niveau, decision) reportee dans risk_assessments. */
    public Outcome decide(BigDecimal score) {
        Decision decision;
        if (score.compareTo(approvedMax) <= 0) {
            decision = Decision.APPROVED;
        } else if (score.compareTo(monitoringMax) <= 0) {
            decision = Decision.MONITORING;
        } else if (score.compareTo(reviewMax) <= 0) {
            decision = Decision.PENDING_REVIEW;
        } else {
            decision = Decision.HOLD;
        }
        return new Outcome(decision, levelOf(decision));
    }

    private static RiskLevel levelOf(Decision decision) {
        return switch (decision) {
            case APPROVED -> RiskLevel.LOW;
            case MONITORING -> RiskLevel.MEDIUM;
            case PENDING_REVIEW -> RiskLevel.HIGH;
            case HOLD -> RiskLevel.CRITICAL;
        };
    }

    public record Outcome(Decision decision, RiskLevel level) {
    }
}
