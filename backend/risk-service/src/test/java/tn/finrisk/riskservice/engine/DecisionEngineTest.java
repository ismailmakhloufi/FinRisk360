package tn.finrisk.riskservice.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskLevel;

/**
 * Tests unitaires du DECISION ENGINE : bornes des 4 bandes et correspondance
 * decision <-> niveau. Les bandes par defaut viennent de application.yml
 * (29 / 59 / 84).
 */
class DecisionEngineTest {

    private final DecisionEngine engine = new DecisionEngine(29, 59, 84);

    private static DecisionEngine.Outcome outcome(Decision decision, RiskLevel level) {
        return new DecisionEngine.Outcome(decision, level);
    }

    @Test
    void bandeApprouvee_0A29() {
        assertThat(engine.decide(BigDecimal.ZERO))
                .isEqualTo(outcome(Decision.APPROVED, RiskLevel.LOW));
        assertThat(engine.decide(new BigDecimal("29")))
                .isEqualTo(outcome(Decision.APPROVED, RiskLevel.LOW));
    }

    @Test
    void bandeMonitoring_30A59() {
        assertThat(engine.decide(new BigDecimal("30")))
                .isEqualTo(outcome(Decision.MONITORING, RiskLevel.MEDIUM));
        assertThat(engine.decide(new BigDecimal("59")))
                .isEqualTo(outcome(Decision.MONITORING, RiskLevel.MEDIUM));
    }

    @Test
    void bandeRevue_60A84() {
        assertThat(engine.decide(new BigDecimal("60")))
                .isEqualTo(outcome(Decision.PENDING_REVIEW, RiskLevel.HIGH));
        assertThat(engine.decide(new BigDecimal("84")))
                .isEqualTo(outcome(Decision.PENDING_REVIEW, RiskLevel.HIGH));
    }

    @Test
    void bandeHold_85A100() {
        assertThat(engine.decide(new BigDecimal("85")))
                .isEqualTo(outcome(Decision.HOLD, RiskLevel.CRITICAL));
        assertThat(engine.decide(BigDecimal.valueOf(100)))
                .isEqualTo(outcome(Decision.HOLD, RiskLevel.CRITICAL));
    }

    @Test
    void bandesPersonnalisees_sansRecompilation() {
        DecisionEngine permissive = new DecisionEngine(49, 79, 99);

        assertThat(permissive.decide(new BigDecimal("49")))
                .isEqualTo(outcome(Decision.APPROVED, RiskLevel.LOW));
        assertThat(permissive.decide(new BigDecimal("99")))
                .isEqualTo(outcome(Decision.PENDING_REVIEW, RiskLevel.HIGH));
        assertThat(permissive.decide(BigDecimal.valueOf(100)))
                .isEqualTo(outcome(Decision.HOLD, RiskLevel.CRITICAL));
    }
}
