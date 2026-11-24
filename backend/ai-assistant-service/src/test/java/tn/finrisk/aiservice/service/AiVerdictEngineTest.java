package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.FactorView;
import tn.finrisk.aiservice.web.dto.AiVerdict;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/** Verdict / confiance / explication : règles déterministes, testées à part. */
class AiVerdictEngineTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-06T14:00:00Z");

    private static EvaluationView evaluation(List<FactorView> factors) {
        return new EvaluationView(1L, 42L, new BigDecimal("87"), "HIGH", "HOLD", T, "v1", factors);
    }

    private static Insight insight(InsightCode code, InsightSeverity severity, String message) {
        return new Insight(code, severity, message, "[source] preuve");
    }

    // ------------------------------------------------------------- verdict

    @Test
    void verdictFraud_whenOneHighSignal() {
        assertEquals(AiVerdict.FRAUDE_SUSPECTE, AiVerdictEngine.verdict(1, 0));
    }

    @Test
    void verdictUncertain_whenTwoMediums() {
        assertEquals(AiVerdict.INCERTAIN, AiVerdictEngine.verdict(0, 2));
    }

    @Test
    void verdictNormal_whenOneMediumOnly() {
        assertEquals(AiVerdict.NORMAL, AiVerdictEngine.verdict(0, 1));
        assertEquals(AiVerdict.NORMAL, AiVerdictEngine.verdict(0, 0));
    }

    // ----------------------------------------------------------- confiance

    @Test
    void confidenceGrowsWithSignals() {
        assertEquals(0.4, AiVerdictEngine.confidence(0, 0, false));
        assertEquals(0.6, AiVerdictEngine.confidence(1, 0, false));
        assertEquals(0.9, AiVerdictEngine.confidence(2, 1, false));
    }

    @Test
    void confidenceCappedAt095() {
        assertEquals(0.95, AiVerdictEngine.confidence(50, 50, false));
    }

    @Test
    void confidenceCappedAt05_whenInsufficientData() {
        // « très confiant » sans données serait un mensonge
        assertEquals(0.5, AiVerdictEngine.confidence(5, 5, true));
    }

    // ---------------------------------------------------------- explication

    @Test
    void explanationContainsScoreFactorsAndVerdict() {
        List<FactorView> factors = List.of(
                new FactorView(1L, "R-AMOUNT", "Montant eleve", "THRESHOLD",
                        new BigDecimal("30.00"), BigDecimal.ONE, "seuil depasse"),
                new FactorView(2L, "R-NIGHT", "Nuit tardive", "BEHAVIOR",
                        new BigDecimal("20.00"), BigDecimal.ONE, "23h55"),
                new FactorView(3L, "R-NEUTRAL", "Regle neutralisee", "THRESHOLD",
                        BigDecimal.ZERO, BigDecimal.ONE, "aucun antecedent"));
        List<Insight> insights = List.of(
                insight(InsightCode.BENEFICIARY_NETWORK, InsightSeverity.HIGH,
                        "Beneficiaire shared."),
                insight(InsightCode.INSUFFICIENT_DATA, InsightSeverity.LOW, "Peu d'historique."));

        String text = AiVerdictEngine.explain(evaluation(factors), insights,
                AiVerdict.FRAUDE_SUSPECTE, 0.6);

        assertTrue(text.contains("Score moteur 87/100 → HOLD"), text);
        assertTrue(text.contains("Montant eleve 30"), text);
        assertTrue(text.contains("Nuit tardive 20"), text);
        assertFalse(text.contains("Regle neutralisee"),
                "facteur a score 0 = rien a expliquer dedans");
        assertTrue(text.contains("Beneficiaire shared."), text);
        assertFalse(text.contains("Peu d'historique."),
                "INSUFFICIENT_DATA n'est pas un signal de fraude");
        assertTrue(text.contains("Avis IA : FRAUDE_SUSPECTE (confiance 0.60)"), text);
    }

    @Test
    void explanationSaysItLoud_whenNoSignal() {
        String text = AiVerdictEngine.explain(evaluation(List.of()), List.of(),
                AiVerdict.NORMAL, 0.4);

        assertTrue(text.contains("aucun signal complementaire")
                        || text.contains("aucun signal complémentaire"),
                text);
        assertTrue(text.contains("Avis IA : NORMAL"), text);
    }
}
