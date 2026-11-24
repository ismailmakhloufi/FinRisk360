package tn.finrisk.riskservice.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tn.finrisk.riskservice.domain.RiskRule;

/**
 * Tests unitaires du SCORING ENGINE : x100, bonus x1,20 des que 3 regles
 * declenchees, plafond 100.
 */
class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();

    private static RuleEvaluation eval(String weight, String factor) {
        RiskRule rule = new RiskRule();
        rule.setWeight(new BigDecimal(weight));
        BigDecimal f = new BigDecimal(factor);
        return new RuleEvaluation(rule, f, null,
                new BigDecimal(weight).multiply(f), "test", f.signum() == 0);
    }

    @Test
    void aucunFacteur_scoreZero() {
        assertThat(engine.score(List.of())).isEqualByComparingTo("0.00");
    }

    @Test
    void uneRegleSeule_pasDeBonus() {
        assertThat(engine.score(List.of(eval("0.25", "1"))))
                .isEqualByComparingTo("25.00");
    }

    @Test
    void troisRegleDeclenchees_bonusVingtPourCent() {
        List<RuleEvaluation> trois =
                List.of(eval("0.10", "1"), eval("0.10", "1"), eval("0.05", "1"));

        // (0,10 + 0,10 + 0,05) x 100 x 1,20 = 30,00
        assertThat(engine.score(trois)).isEqualByComparingTo("30.00");
    }

    @Test
    void plafondCentMemeAvecBonus() {
        List<RuleEvaluation> cumul = List.of(
                eval("0.40", "1"), eval("0.40", "1"), eval("0.10", "1"), eval("0.10", "1"));

        // 100 x 1,20 = 120 -> ramene au plafond 100
        assertThat(engine.score(cumul)).isEqualByComparingTo("100.00");
    }

    @Test
    void facteurZeroNeComptePasPourLeBonus() {
        List<RuleEvaluation> deux = List.of(
                eval("0.50", "1"), eval("0.30", "1"), eval("0.10", "0"));

        // 3 evaluations mais 2 declenchees seulement -> pas de bonus
        assertThat(engine.score(deux)).isEqualByComparingTo("80.00");
    }
}
