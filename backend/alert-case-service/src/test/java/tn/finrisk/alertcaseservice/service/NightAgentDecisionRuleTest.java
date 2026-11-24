package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.service.NightAgentDecisionRule.Outcome;

/**
 * La matrice de l'agent nocturne, testee SEULE (pur, aucune dependance) :
 * c'est le contrat de securite du feature — un changement de regle casse
 * forcement un test ici.
 */
class NightAgentDecisionRuleTest {

    // ------------------------------------------------- CAS D'ECRITURE (1)

    @Test
    void lowEtIaNormal_valide() {
        Outcome o = NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90);
        assertThat(o.action()).isEqualTo(AgentAction.VALIDATED);
        assertThat(o.reason()).contains("LOW").contains("90%");
    }

    @Test
    void mediumEtIaNormal_valide() {
        Outcome o = NightAgentDecisionRule.decide("MEDIUM", "NORMAL", 0.75);
        assertThat(o.action()).isEqualTo(AgentAction.VALIDATED);
        assertThat(o.reason()).contains("MEDIUM");
    }

    // --------------------------------------------------- CAS DE REPOI

    @Test
    void critical_toujoursLaisse() {
        Outcome o = NightAgentDecisionRule.decide("CRITICAL", "NORMAL", 0.99);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("CRITICAL");
    }

    @Test
    void iaFraudeSurLow_laisse() {
        // L'IA voit du risque la ou le moteur dit LOW : elle prime.
        Outcome o = NightAgentDecisionRule.decide("LOW", "FRAUDE_SUSPECTE", 0.80);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("fraude");
    }

    @Test
    void iaIndisponible_laisse() {
        Outcome o = NightAgentDecisionRule.decide("LOW", null, null);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("indisponible");
    }

    @Test
    void lowEtIaIncertain_laisse() {
        Outcome o = NightAgentDecisionRule.decide("LOW", "INCERTAIN", 0.55);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("hesite");
    }

    @Test
    void divergenceHighMaisIaRassurante_laissePrioritaire() {
        Outcome o = NightAgentDecisionRule.decide("HIGH", "NORMAL", 0.85);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("Divergence");
    }

    @Test
    void highEtIaIncertain_laisse() {
        Outcome o = NightAgentDecisionRule.decide("HIGH", "INCERTAIN", 0.60);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("HIGH");
    }

    @Test
    void highEtIaFraude_laisse() {
        Outcome o = NightAgentDecisionRule.decide("HIGH", "FRAUDE_SUSPECTE", 0.90);
        assertThat(o.action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(o.reason()).contains("fraude");
    }

    @Test
    void touteConfigurationNeValideJamaisAutreQueLowMediumNormal() {
        // Propriete : la seule paire qui ecrit est (LOW|MEDIUM) + NORMAL.
        String[] levels = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
        String[] verdicts = {"NORMAL", "INCERTAIN", "FRAUDE_SUSPECTE", null};
        for (String level : levels) {
            for (String verdict : verdicts) {
                Outcome o = NightAgentDecisionRule.decide(level, verdict, 0.5);
                boolean ecriture = o.action() == AgentAction.VALIDATED;
                boolean attendu = ("LOW".equals(level) || "MEDIUM".equals(level))
                        && "NORMAL".equals(verdict);
                assertThat(ecriture)
                        .as("level=%s verdict=%s", level, verdict)
                        .isEqualTo(attendu);
            }
        }
    }
}
