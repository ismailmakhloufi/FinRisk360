package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tn.finrisk.alertcaseservice.service.AlertTriageRule.Priority;
import tn.finrisk.alertcaseservice.service.AlertTriageRule.Recommendation;

class AlertTriageRuleTest {
    @Test
    void risqueCritiqueToujoursUrgent() {
        assertThat(AlertTriageRule.classify("CRITICAL", "NORMAL"))
                .extracting(AlertTriageRule.Result::priority, AlertTriageRule.Result::recommendation)
                .containsExactly(Priority.URGENT, Recommendation.HUMAN_REVIEW);
    }

    @Test
    void risqueEleveToujoursExamineParAnalyste() {
        assertThat(AlertTriageRule.classify("HIGH", "NORMAL").recommendation())
                .isEqualTo(Recommendation.HUMAN_REVIEW);
        assertThat(AlertTriageRule.classify("HIGH", null).priority()).isEqualTo(Priority.HIGH);
    }

    @Test
    void seulsRisquesFaibleOuMoyenAvecAvisNormalSontEligibles() {
        for (String risk : new String[] {"LOW", "MEDIUM"}) {
            assertThat(AlertTriageRule.classify(risk, "NORMAL").recommendation())
                    .isEqualTo(Recommendation.ELIGIBLE_FOR_AUTOMATION);
            for (String verdict : new String[] {null, "INCERTAIN", "FRAUDE_SUSPECTE", "INCONNU"}) {
                assertThat(AlertTriageRule.classify(risk, verdict).recommendation())
                        .isEqualTo(Recommendation.HUMAN_REVIEW);
            }
        }
    }

    @Test
    void fraudeEtRisqueInconnuSontPrioritaires() {
        assertThat(AlertTriageRule.classify("LOW", "FRAUDE_SUSPECTE").priority()).isEqualTo(Priority.HIGH);
        assertThat(AlertTriageRule.classify(null, "NORMAL").recommendation())
                .isEqualTo(Recommendation.HUMAN_REVIEW);
    }
}
