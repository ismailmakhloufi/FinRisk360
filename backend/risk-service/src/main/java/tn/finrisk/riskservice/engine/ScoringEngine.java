package tn.finrisk.riskservice.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ③ SCORING ENGINE : score = (somme des contributions) x 100,
 *    BONUS de cumul x1,20 des que 3 regles sont declenchees, plafond 100.
 *
 * Le bonus traduit la regle de l'enqueteur : un indice ne prouve rien,
 * leur ENSEMBLE fait le dossier. Concretement il fait passer un 60 a 72,
 * un 71 a 85 (bascule HOLD).
 *
 * Plafond 100 : utile si un administrateur pousse la somme des poids au-dela
 * de 1.00 via PUT /api/rules (le score reste un pourcentage borné).
 */
@Component
public class ScoringEngine {

    private static final BigDecimal BONUS = new BigDecimal("1.20");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int BONUS_MIN_RULES = 3;

    public BigDecimal score(List<RuleEvaluation> evaluations) {
        BigDecimal sum = evaluations.stream()
                .map(RuleEvaluation::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal score = sum.multiply(HUNDRED);

        long triggered = evaluations.stream()
                .filter(RuleEvaluation::triggered)
                .count();
        if (triggered >= BONUS_MIN_RULES) {
            score = score.multiply(BONUS);
        }
        if (score.compareTo(HUNDRED) > 0) {
            score = HUNDRED;
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }
}
