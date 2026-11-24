package tn.finrisk.riskservice.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tn.finrisk.riskservice.domain.RiskRule;
import tn.finrisk.riskservice.domain.enums.ComparisonOperator;

/**
 * ② RULE ENGINE : applique chaque regle activee aux features, en NOTATION GRADUEE.
 *
 * Loi unique (identique pour toutes les regles numeriques) :
 *   '>'  ou '>=' : valeur <= seuil -> 0 | <= 1,5x seuil -> 0,5 | au-dela -> 1
 *   '<'  ou '<=' : valeur >  seuil -> 0 | >   seuil/1,5 -> 0,5 | en-deca -> 1
 *   '='  (RULE-003 pays)  : binaire 0 / 1 (pays == pays habituel -> 0)
 *   sans seuil   : binaire 0 / 1 (marchand du client)
 *
 * Le facteur (0 | 0,5 | 1) est multiplie par le POIDS de la regle :
 * contribution = weight x factor, somme a x100 par le ScoringEngine.
 *
 * Les regles NON applicables (pas d'antecedent, devise non TND, sigma nul...)
 * ne sont JAMAIS ignorees silencieusement : factor 0 + neutralized=true,
 * la raison part dans risk_factors (explicabilite).
 *
 * Les codes inconnus (regle ajoutee en base sans support du moteur) sont
 * neutralises aussi : jamais d'erreur, jamais de score faux.
 */
@Component
public class RuleEngine {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** Plancher de cumul pour RULE-012 : sans lui, 10 micro-virements declencheraient la regle a vide. */
    private final BigDecimal structuringMinCumul;
    /** Miroir de risk.structuring.small-amount-max (FeatureEngine) : plafond du "petit montant". */
    private final BigDecimal smallAmountMax;

    public RuleEngine(
            @Value("${risk.structuring.min-cumul:500}") BigDecimal structuringMinCumul,
            @Value("${risk.structuring.small-amount-max:5000}") BigDecimal smallAmountMax) {
        this.structuringMinCumul = structuringMinCumul;
        this.smallAmountMax = smallAmountMax;
    }

    public List<RuleEvaluation> evaluate(TransactionFeatures features, List<RiskRule> enabledRules) {
        if (enabledRules == null || enabledRules.isEmpty()) {
            throw new NoEnabledRulesException();
        }
        return enabledRules.stream()
                .map(rule -> evaluateOne(rule, features))
                .toList();
    }

    private RuleEvaluation evaluateOne(RiskRule rule, TransactionFeatures f) {
        return switch (rule.getRuleCode()) {
            case "RULE-001" -> amountHigh(rule, f, "Montant " + f.transaction().amount() + " TND");
            case "RULE-002" -> numeric(rule,
                    BigDecimal.valueOf(f.activity().txCount24h()),
                    "Velocite 24h glissantes = " + f.activity().txCount24h() + " transactions");
            case "RULE-003" -> countryRule(rule, f);
            case "RULE-004" -> numeric(rule, BigDecimal.valueOf(f.localHour()),
                    "Heure locale " + f.localHour() + "h");
            case "RULE-005" -> merchantAgeRule(rule, f);
            case "RULE-006" -> deviationRule(rule, f);
            case "RULE-007" -> p95Rule(rule, f);
            case "RULE-008" -> cumul24hRule(rule, f);
            case "RULE-009" -> duplicateRule(rule, f);
            case "RULE-010" -> numeric(rule,
                    BigDecimal.valueOf(f.activity().failedCount24h()),
                    "Echecs 24h glissantes = " + f.activity().failedCount24h());
            case "RULE-011" -> countryGapRule(rule, f);
            case "RULE-012" -> structuringRule(rule, f);
            default -> neutral(rule, "Code de regle inconnu du moteur : " + rule.getRuleCode());
        };
    }

    // ---------------------------------------------------------------- AMONT
    // Regles qui passent par une valeur numerique vs threshold_value.

    /** RULE-001 : montant (TND uniquement, seuils exprimes en TND). */
    private RuleEvaluation amountHigh(RiskRule rule, TransactionFeatures f, String label) {
        if (!f.tnd()) {
            return neutral(rule, "Devise " + f.transaction().currency()
                    + " : seuils montant exprimes en TND, regle non applicable");
        }
        return numeric(rule, f.transaction().amount(), label);
    }

    /** Moteur gradue : depend de l'operateur de la regle. */
    private RuleEvaluation numeric(RiskRule rule, BigDecimal value, String label) {
        ComparisonOperator op = rule.getComparisonOperator();
        BigDecimal threshold = rule.getThresholdValue();

        if (op == null || threshold == null) {
            return neutral(rule, "Regle numerique sans operateur/seuil : configuration invalide");
        }

        BigDecimal factor;
        boolean fired;
        switch (op) {
            case GREATER_THAN -> {
                fired = value.compareTo(threshold) > 0;
                factor = !fired ? ZERO
                        : value.compareTo(threshold.multiply(new BigDecimal("1.5"))) <= 0
                                ? HALF : BigDecimal.ONE;
            }
            case GREATER_THAN_OR_EQUAL -> {
                fired = value.compareTo(threshold) >= 0;
                factor = !fired ? ZERO
                        : value.compareTo(threshold.multiply(new BigDecimal("1.5"))) < 0
                                ? HALF : BigDecimal.ONE;
            }
            case LESS_THAN -> {
                fired = value.compareTo(threshold) < 0;
                factor = !fired ? ZERO
                        : value.compareTo(threshold.divide(new BigDecimal("1.5"), 4, RoundingMode.HALF_UP)) > 0
                                ? HALF : BigDecimal.ONE;
            }
            case LESS_THAN_OR_EQUAL -> {
                fired = value.compareTo(threshold) <= 0;
                factor = !fired ? ZERO
                        : value.compareTo(threshold.divide(new BigDecimal("1.5"), 4, RoundingMode.HALF_UP)) > 0
                                ? HALF : BigDecimal.ONE;
            }
            case EQUAL -> {
                fired = value.compareTo(threshold) == 0;
                factor = fired ? BigDecimal.ONE : ZERO;
            }
            default -> {
                return neutral(rule, "Operateur non supporte par le moteur : " + op);
            }
        }

        String zone = factor.signum() == 0 ? "non declenchee"
                : factor.compareTo(HALF) == 0 ? "zone demi" : "zone pleine";
        String reason = label + " vs seuil " + threshold + " (" + op.getSymbol() + ") : " + zone
                + " (+" + contributionOf(rule, factor).toPlainString() + ")";
        return new RuleEvaluation(rule, factor, value, contributionOf(rule, factor), reason, false);
    }

    // ------------------------------------------------- REGLES SPECIALISEES

    /** RULE-003 : pays != pays habituel du profil. */
    private RuleEvaluation countryRule(RiskRule rule, TransactionFeatures f) {
        if (!f.hasBaseline() || f.stats().usualCountry() == null) {
            return neutral(rule, "Pas d'antecedent : pays habituel inconnu");
        }
        String current = f.transaction().country();
        String usual = f.stats().usualCountry();
        boolean fired = current != null && !current.equals(usual);
        BigDecimal factor = fired ? BigDecimal.ONE : ZERO;
        String reason = fired
                ? "Pays " + current + " != pays habituel " + usual : "Pays " + current + " = pays habituel";
        return new RuleEvaluation(rule, factor, null, contributionOf(rule, factor), reason, false);
    }

    /** RULE-005 : marchand cree depuis <= seuil jours. */
    private RuleEvaluation merchantAgeRule(RiskRule rule, TransactionFeatures f) {
        if (f.merchantAgeDays() == null) {
            return neutral(rule, "Pas de marchand sur cette transaction");
        }
        return numeric(rule, BigDecimal.valueOf(f.merchantAgeDays()),
                "Age du marchand = " + f.merchantAgeDays() + " jours");
    }

    /** RULE-006 : |montant - moyenne| / sigma > 2.5. */
    private RuleEvaluation deviationRule(RiskRule rule, TransactionFeatures f) {
        if (!f.tnd()) {
            return neutral(rule, "Devise " + f.transaction().currency()
                    + " : profil calcule en TND, regle non applicable");
        }
        if (!f.hasBaseline()) {
            return neutral(rule, "Pas d'antecedent : ecart au profil non mesurable");
        }
        BigDecimal sigma = f.stats().sigma();
        if (sigma == null || sigma.signum() == 0) {
            return neutral(rule, "Ecart-type nul (transactions identiques) : ecart non mesurable");
        }
        BigDecimal diff = f.transaction().amount().subtract(f.stats().avg()).abs();
        BigDecimal z = diff.divide(sigma, 2, RoundingMode.HALF_UP);
        return numeric(rule, z, "Ecart au profil = " + z + " sigma (moyenne "
                + f.stats().avg() + ", sigma " + sigma + ")");
    }

    /** RULE-007 : montant vs P95 DYNAMIQUE du client (pas de seuil en base). */
    private RuleEvaluation p95Rule(RiskRule rule, TransactionFeatures f) {
        if (!f.tnd()) {
            return neutral(rule, "Devise " + f.transaction().currency()
                    + " : seuils montant exprimes en TND, regle non applicable");
        }
        if (!f.hasBaseline() || f.stats().p95() == null) {
            return neutral(rule, "Pas d'antecedent : P95 inconnu");
        }
        BigDecimal p95 = f.stats().p95();
        BigDecimal value = f.transaction().amount();
        BigDecimal factor;
        if (value.compareTo(p95) <= 0) {
            factor = ZERO;
        } else if (value.compareTo(p95.multiply(new BigDecimal("1.5"))) <= 0) {
            factor = HALF;
        } else {
            factor = BigDecimal.ONE;
        }
        String zone = factor.signum() == 0 ? "non declenchee"
                : factor.compareTo(HALF) == 0 ? "zone demi" : "zone pleine";
        String reason = "Montant " + value + " TND vs P95 client " + p95 + " TND : " + zone
                + " (+" + contributionOf(rule, factor).toPlainString() + ")";
        return new RuleEvaluation(rule, factor, value, contributionOf(rule, factor), reason, false);
    }

    /** RULE-008 : cumul 24h vs multiple (seuil = le multiple) x depense habituelle. */
    private RuleEvaluation cumul24hRule(RiskRule rule, TransactionFeatures f) {
        if (!f.tnd()) {
            return neutral(rule, "Devise " + f.transaction().currency()
                    + " : cumuls exprimes en TND, regle non applicable");
        }
        if (!f.hasBaseline()) {
            return neutral(rule, "Pas d'antecedent : depense habituelle inconnue");
        }
        BigDecimal expectedDaily = f.stats().avg()
                .multiply(f.stats().transactionsPerDay())
                .setScale(2, RoundingMode.HALF_UP);
        if (expectedDaily.signum() <= 0) {
            return neutral(rule, "Depense habituelle nulle : multiple non applicable");
        }
        BigDecimal multiple = rule.getThresholdValue() == null
                ? BigDecimal.ONE : rule.getThresholdValue();
        BigDecimal reference = expectedDaily.multiply(multiple).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cumul = f.activity().cumul24hTnd();

        BigDecimal factor;
        if (cumul.compareTo(reference) <= 0) {
            factor = ZERO;
        } else if (cumul.compareTo(reference.multiply(new BigDecimal("1.5"))) <= 0) {
            factor = HALF;
        } else {
            factor = BigDecimal.ONE;
        }
        String zone = factor.signum() == 0 ? "non declenchee"
                : factor.compareTo(HALF) == 0 ? "zone demi" : "zone pleine";
        String reason = "Cumul 24h = " + cumul + " TND vs " + multiple + "x la depense habituelle ("
                + reference + " TND) : " + zone + " (+" + contributionOf(rule, factor).toPlainString() + ")";
        return new RuleEvaluation(rule, factor, cumul, contributionOf(rule, factor), reason, false);
    }

    /** RULE-009 : deux memes (marchand, montant) rapproches de <= seuil minutes. */
    private RuleEvaluation duplicateRule(RiskRule rule, TransactionFeatures f) {
        Integer gap = f.activity().minGapDuplicateMinutes();
        if (gap == null) {
            return new RuleEvaluation(rule, ZERO, null, ZERO,
                    "Aucun couple (meme marchand, meme montant) sur la fenetre", false);
        }
        return numeric(rule, BigDecimal.valueOf(gap),
                "Ecart entre deux transactions identiques = " + gap + " min");
    }

    /** RULE-011 : deux pays differents rapproches de <= seuil minutes. */
    private RuleEvaluation countryGapRule(RiskRule rule, TransactionFeatures f) {
        Integer gap = f.activity().minGapCountryMinutes();
        if (gap == null) {
            return new RuleEvaluation(rule, ZERO, null, ZERO,
                    "Aucun couple de transactions dans deux pays differents", false);
        }
        return numeric(rule, BigDecimal.valueOf(gap),
                    "Ecart entre deux pays differents = " + gap + " min");
    }

    /**
     * RULE-012 : STRUCTURATION (fractionnement sous le seuil de declaration).
     *
     * Detecte le MOTIF, pas le montant : N petites transactions sortantes
     * rapprochees dans la meme fenetre 24h glissante. Chacune prise isolement
     * est en dessous de RULE-001 et ne declenche donc RIEN — c'est tout
     * l'interet de la regle : sans elle, 10 virements de 100 TND notent ~5/100
     * (LOW) alors que leur cumul (1000 TND) est un fractionnement manifeste.
     *
     * Seuil = nombre de petites transactions (threshold_value, gradue 0 / 0,5 / 1
     * par la loi numerique usuelle).
     * Garde-fou : le cumul de ces memes transactions doit atteindre un plancher
     * (risk.structuring.min-cumul), sinon 10 micro-virements de 0,01 TND
     * declencheraient la regle a vide.
     */
    private RuleEvaluation structuringRule(RiskRule rule, TransactionFeatures f) {
        if (!f.tnd()) {
            return neutral(rule, "Devise " + f.transaction().currency()
                    + " : structuration exprimee en TND, regle non applicable");
        }
        int count = f.activity().smallTxCount24h();
        BigDecimal sum = f.activity().smallTxSum24h();
        if (sum.compareTo(structuringMinCumul) < 0) {
            return neutral(rule, "Cumul de petites transactions = " + sum
                    + " TND : sous le plancher de " + structuringMinCumul
                    + " TND, pas de fractionnement significatif");
        }
        return numeric(rule, BigDecimal.valueOf(count),
                count + " petites transactions sortantes (<= " + smallAmountMax
                        + " TND) en 24h glissantes, cumul " + sum + " TND");
    }

    // --------------------------------------------------------------- OUTILS

    private RuleEvaluation neutral(RiskRule rule, String why) {
        return new RuleEvaluation(rule, ZERO, null, ZERO,
                "Regle neutralisee : " + why, true);
    }

    private BigDecimal contributionOf(RiskRule rule, BigDecimal factor) {
        return rule.getWeight().multiply(factor).setScale(4, RoundingMode.HALF_UP);
    }
}
