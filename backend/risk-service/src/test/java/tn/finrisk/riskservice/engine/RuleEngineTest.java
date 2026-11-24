package tn.finrisk.riskservice.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tn.finrisk.riskservice.client.dto.TransactionView;
import tn.finrisk.riskservice.domain.RiskRule;
import tn.finrisk.riskservice.domain.enums.ComparisonOperator;
import tn.finrisk.riskservice.domain.enums.RiskCategory;

/**
 * Tests unitaires du RULE ENGINE : notation graduee, neutralisations
 * explicites, jamais d'erreur silencieuse. Les features sont produites par le
 * VRAI FeatureEngine : on teste l'assemblage reel du moteur.
 */
class RuleEngineTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");

    private final FeatureEngine featureEngine = new FeatureEngine("UTC", 90, new BigDecimal("5000"));
    private final RuleEngine ruleEngine = new RuleEngine(new BigDecimal("500"), new BigDecimal("5000"));

    private static RiskRule rule(String code, ComparisonOperator op,
                                 String threshold, String weight) {
        RiskRule rule = new RiskRule();
        rule.setRuleId(1L);
        rule.setRuleCode(code);
        rule.setRuleName("regle " + code);
        rule.setRiskCategory(RiskCategory.AMOUNT);
        rule.setComparisonOperator(op);
        rule.setThresholdValue(threshold == null ? null : new BigDecimal(threshold));
        rule.setWeight(new BigDecimal(weight));
        rule.setEnabled(Boolean.TRUE);
        return rule;
    }

    private static TransactionView tx(long id, String amount, String country,
                                      OffsetDateTime date, Long merchantId) {
        return new TransactionView(id, 1L, merchantId, new BigDecimal(amount), "TND",
                country, date, "PAYMENT", "COMPLETED", "WEB");
    }

    private TransactionFeatures features(TransactionView self, List<TransactionView> history,
                                          Integer merchantAge) {
        return featureEngine.compute(self, 10L, history, merchantAge);
    }

    private RuleEvaluation eval(RiskRule rule, TransactionFeatures f) {
        List<RuleEvaluation> results = ruleEngine.evaluate(f, List.of(rule));
        return results.getFirst();
    }

    // ------------------------------------------------------------- GRADATION

    @Test
    void gradationOperateurSuperieur_troisZones() {
        RiskRule r = rule("RULE-001", ComparisonOperator.GREATER_THAN, "5000", "0.25");

        TransactionView sous = tx(1, "4000.00", "TN", T, null);
        assertThat(eval(r, features(sous, List.of(sous), null)).factor())
                .isEqualByComparingTo("0");

        TransactionView demi = tx(2, "6000.00", "TN", T, null);
        RuleEvaluation zoneDemi = eval(r, features(demi, List.of(demi), null));
        assertThat(zoneDemi.factor()).isEqualByComparingTo("0.5");
        assertThat(zoneDemi.contribution()).isEqualByComparingTo("0.1250");
        assertThat(zoneDemi.reason()).contains("zone demi");

        TransactionView plein = tx(3, "9000.00", "TN", T, null);
        RuleEvaluation zonePleine = eval(r, features(plein, List.of(plein), null));
        assertThat(zonePleine.factor()).isEqualByComparingTo("1");
        assertThat(zonePleine.triggered()).isTrue();
        assertThat(zonePleine.reason()).contains("zone pleine");
    }

    @Test
    void gradationOperateurInferieur_heureDeNuit() {
        RiskRule r = rule("RULE-004", ComparisonOperator.LESS_THAN_OR_EQUAL, "5", "0.05");

        TransactionView jour = tx(1, "40.00", "TN",
                OffsetDateTime.parse("2026-01-15T20:00:00Z"), null);
        assertThat(eval(r, features(jour, List.of(jour), null)).factor())
                .isEqualByComparingTo("0");

        // 4h : au-dela de 5/1,5 = 3,33 -> zone demi
        TransactionView nuit = tx(2, "40.00", "TN",
                OffsetDateTime.parse("2026-01-15T04:00:00Z"), null);
        assertThat(eval(r, features(nuit, List.of(nuit), null)).factor())
                .isEqualByComparingTo("0.5");

        TransactionView profond = tx(3, "40.00", "TN",
                OffsetDateTime.parse("2026-01-15T02:00:00Z"), null);
        assertThat(eval(r, features(profond, List.of(profond), null)).factor())
                .isEqualByComparingTo("1");
    }

    @Test
    void reglePays_operateurEgalBinaire() {
        RiskRule r = rule("RULE-003", ComparisonOperator.EQUAL, null, "0.10");

        TransactionView self = tx(3, "9000.00", "DE", T, null);
        TransactionView t1 = tx(1, "40.00", "TN", T.minusDays(1), null);
        TransactionView t2 = tx(2, "40.00", "TN", T.minusDays(2), null);
        RuleEvaluation pays = eval(r, features(self, List.of(self, t1, t2), null));
        assertThat(pays.factor()).isEqualByComparingTo("1");
        assertThat(pays.reason()).contains("pays habituel TN");

        TransactionView tn = tx(4, "40.00", "TN", T, null);
        TransactionView ancien = tx(5, "40.00", "TN", T.minusDays(1), null);
        assertThat(eval(r, features(tn, List.of(tn, ancien), null)).factor())
                .isEqualByComparingTo("0");
    }

    // -------------------------------------------------------- NEUTRALISATIONS

    @Test
    void sansAntecedent_quatreReglesNeutralisees() {
        List<RiskRule> rules = List.of(
                rule("RULE-003", ComparisonOperator.EQUAL, null, "0.10"),
                rule("RULE-006", ComparisonOperator.GREATER_THAN, "2.5", "0.05"),
                rule("RULE-007", ComparisonOperator.GREATER_THAN, "0", "0.10"),
                rule("RULE-008", ComparisonOperator.GREATER_THAN, "3", "0.05"));
        TransactionView self = tx(1, "40.00", "TN", T, null);

        List<RuleEvaluation> results =
                ruleEngine.evaluate(features(self, List.of(self), null), rules);

        assertThat(results).allMatch(RuleEvaluation::neutralized);
        assertThat(results).noneMatch(RuleEvaluation::triggered);
        assertThat(results.getFirst().reason())
                .contains("Regle neutralisee").contains("antecedent");
        assertThat(results.getFirst().worthPersisting()).isTrue(); // explicable
    }

    @Test
    void deviseNonTnd_neutralisee() {
        RiskRule r = rule("RULE-001", ComparisonOperator.GREATER_THAN, "5000", "0.25");
        TransactionView self = new TransactionView(1L, 1L, null, new BigDecimal("8000"),
                "EUR", "TN", T, "PAYMENT", "COMPLETED", "WEB");

        RuleEvaluation e = eval(r, features(self, List.of(self), null));

        assertThat(e.neutralized()).isTrue();
        assertThat(e.reason()).contains("Devise EUR");
    }

    @Test
    void sansMarchand_neutralisee() {
        RiskRule r = rule("RULE-005", ComparisonOperator.LESS_THAN_OR_EQUAL, "30", "0.05");
        TransactionView self = tx(1, "40.00", "TN", T, null);

        RuleEvaluation e = eval(r, features(self, List.of(self), null));

        assertThat(e.neutralized()).isTrue();
        assertThat(e.reason()).contains("Pas de marchand");
    }

    @Test
    void ecartTypeNul_neutralisee() {
        RiskRule r = rule("RULE-006", ComparisonOperator.GREATER_THAN, "2.5", "0.05");
        TransactionView self = tx(3, "8000.00", "TN", T, null);
        TransactionView h1 = tx(1, "8000.00", "TN", T.minusDays(1), null);
        TransactionView h2 = tx(2, "8000.00", "TN", T.minusDays(2), null);

        RuleEvaluation e = eval(r, features(self, List.of(self, h1, h2), null));

        assertThat(e.neutralized()).isTrue();
        assertThat(e.reason()).contains("Ecart-type nul");
    }

    @Test
    void codeInconnu_neutraliseJamaisErreur() {
        RiskRule r = rule("RULE-999", ComparisonOperator.GREATER_THAN, "10", "0.10");
        TransactionView self = tx(1, "40.00", "TN", T, null);

        RuleEvaluation e = eval(r, features(self, List.of(self), null));

        assertThat(e.neutralized()).isTrue();
        assertThat(e.reason()).contains("inconnu du moteur");
    }

    @Test
    void aucuneRegleActivee_leve() {
        TransactionView self = tx(1, "40.00", "TN", T, null);

        assertThatThrownBy(() -> ruleEngine.evaluate(
                features(self, List.of(self), null), List.of()))
                .isInstanceOf(NoEnabledRulesException.class);
    }

    // -------------------------------------------------------- REGLES SPECIFIQUES

    @Test
    void rule009_doublonDansLaFenetre() {
        RiskRule r = rule("RULE-009", ComparisonOperator.LESS_THAN_OR_EQUAL, "10", "0.05");
        TransactionView jumeau = tx(1, "50.00", "TN", T.minusMinutes(10), 777L);
        TransactionView self = tx(2, "50.00", "TN", T.minusMinutes(5), 777L);

        RuleEvaluation e = eval(r, features(self, List.of(self, jumeau), null));

        assertThat(e.factor()).isEqualByComparingTo("1"); // 5 min : pleine zone
        assertThat(e.triggered()).isTrue();
    }

    @Test
    void rule011_ecartEntrePaysDifferents() {
        RiskRule r = rule("RULE-011", ComparisonOperator.LESS_THAN_OR_EQUAL, "180", "0.10");

        TransactionView avant = tx(1, "40.00", "TN", T.minusMinutes(60), null);
        TransactionView proche = tx(2, "9000.00", "DE", T, null);
        assertThat(eval(r, features(proche, List.of(proche, avant), null)).factor())
                .isEqualByComparingTo("1");

        TransactionView avantLoin = tx(3, "40.00", "TN", T.minusMinutes(400), null);
        TransactionView loin = tx(4, "9000.00", "DE", T, null);
        RuleEvaluation nonDeclenchee = eval(r, features(loin, List.of(loin, avantLoin), null));
        assertThat(nonDeclenchee.factor()).isEqualByComparingTo("0");
        assertThat(nonDeclenchee.neutralized()).isFalse(); // donnee presente, seuil non atteint

        TransactionView seul = tx(5, "40.00", "TN", T, null);
        RuleEvaluation aucun = eval(r, features(seul, List.of(seul), null));
        assertThat(aucun.reason()).contains("Aucun couple");
        assertThat(aucun.neutralized()).isFalse();
    }

    // -------------------------------------------------------- RULE-012 STRUCTURATION

    private static TransactionView virement(long id, String amount, OffsetDateTime date) {
        return new TransactionView(id, 1L, null, new BigDecimal(amount), "TND",
                "TN", date, "TRANSFER", "COMPLETED", "WEB");
    }

    private static List<TransactionView> petites(int n, String amount) {
        List<TransactionView> history = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            history.add(virement(i, amount, T.minusHours(i)));
        }
        return history;
    }

    @Test
    void rule012_dixPetitsVirements_zonePleine() {
        // SCENARIO DE REFERENCE : 10 x 100 TND. Chaque montant pris isolement
        // est en dessous de RULE-001 : seul le MOTIF (fractionnement) compte.
        RiskRule r = rule("RULE-012", ComparisonOperator.GREATER_THAN, "3", "0.30");
        TransactionView self = virement(0, "100.00", T);
        List<TransactionView> history = new java.util.ArrayList<>(petites(9, "100.00"));
        history.add(self); // 9 + la transaction evaluee = 10

        RuleEvaluation e = eval(r, features(self, history, null));

        assertThat(e.triggered()).isTrue();
        assertThat(e.factor()).isEqualByComparingTo("1");       // 10 > 3 x 1,5
        assertThat(e.contribution()).isEqualByComparingTo("0.3000");
        assertThat(e.reason()).contains("10 petites transactions sortantes");
        assertThat(e.reason()).contains("1000.00");
        assertThat(e.neutralized()).isFalse();
    }

    @Test
    void rule012_zoneDemi() {
        // 4 x 200 = 800 TND : passe le plancher 500, reste dans la zone demi
        RiskRule r = rule("RULE-012", ComparisonOperator.GREATER_THAN, "3", "0.30");
        TransactionView self = virement(0, "200.00", T);
        List<TransactionView> history = new java.util.ArrayList<>(petites(3, "200.00"));
        history.add(self); // 3 + la transaction evaluee = 4

        RuleEvaluation e = eval(r, features(self, history, null));

        assertThat(e.triggered()).isTrue();
        assertThat(e.factor()).isEqualByComparingTo("0.5");     // 4 <= 4,5
    }

    @Test
    void rule012_troisVirements_nonDeclenchee() {
        // 3 x 200 = 600 TND : le plancher n'est pas l'obstacle, c'est le
        // seuil > 3 qui n'est pas atteint (3 = seuil, strict).
        RiskRule r = rule("RULE-012", ComparisonOperator.GREATER_THAN, "3", "0.30");
        TransactionView self = virement(0, "200.00", T);
        List<TransactionView> history = new java.util.ArrayList<>(petites(2, "200.00"));
        history.add(self);

        RuleEvaluation e = eval(r, features(self, history, null));

        assertThat(e.triggered()).isFalse();
        assertThat(e.factor()).isEqualByComparingTo("0");
        assertThat(e.neutralized()).isFalse(); // donnee presente, seuil non atteint
    }

    @Test
    void rule012_sousLePlancherDeCumul_neutralisee() {
        // Garde-fou : 10 micro-virements de 20 TND = 200 TND < plancher 500.
        // Sans ce garde-fou la regle declencherait a vide sur n'importe quel
        // client qui paie son cafe.
        RiskRule r = rule("RULE-012", ComparisonOperator.GREATER_THAN, "3", "0.30");
        TransactionView self = virement(0, "20.00", T);
        List<TransactionView> history = new java.util.ArrayList<>(petites(10, "20.00"));
        history.add(self);

        RuleEvaluation e = eval(r, features(self, history, null));

        assertThat(e.neutralized()).isTrue();
        assertThat(e.reason()).contains("plancher");
        assertThat(e.triggered()).isFalse();
    }

    @Test
    void rule012_deviseNonTnd_neutralisee() {
        RiskRule r = rule("RULE-012", ComparisonOperator.GREATER_THAN, "3", "0.30");
        TransactionView self = new TransactionView(0L, 1L, null, new BigDecimal("100"),
                "EUR", "TN", T, "TRANSFER", "COMPLETED", "WEB");

        RuleEvaluation e = eval(r, features(self, List.of(self), null));

        assertThat(e.neutralized()).isTrue();
        assertThat(e.reason()).contains("Devise EUR");
    }

    @Test
    void rule012_unSeulGrandVirement_nonDeclenchee() {
        // Un virement de 8000 TND est DU GROS MONTANT : c'est RULE-001, pas
        // de la structuration. Il ne compte pas comme "petite transaction".
        RiskRule r = rule("RULE-012", ComparisonOperator.GREATER_THAN, "3", "0.30");
        TransactionView self = virement(0, "8000.00", T);

        RuleEvaluation e = eval(r, features(self, List.of(self), null));

        assertThat(e.triggered()).isFalse();
        assertThat(e.factor()).isEqualByComparingTo("0");
    }
}
