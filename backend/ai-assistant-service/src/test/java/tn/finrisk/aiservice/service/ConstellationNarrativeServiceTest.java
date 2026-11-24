package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tn.finrisk.aiservice.client.LlmClient;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.service.ConstellationGraphService.Graph;
import tn.finrisk.aiservice.service.dto.AnalysisContext;
import tn.finrisk.aiservice.web.dto.ConstellationNarrative;
import tn.finrisk.aiservice.web.dto.ConstellationNode;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Tests du narratif : parsing défensif du JSON LLM (fences, texte parasite,
 * JSON cassé), VALIDATION des focusNodes contre le graphe réel, qualité des
 * données (et plus une pseudo-confiance mesurant la longueur du texte), et
 * complétude du repli déterministe en français.
 */
class ConstellationNarrativeServiceTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-06T14:00:00Z");
    private final ConstellationGraphService graphService = new ConstellationGraphService();

    // ------------------------------------------------------------ fixtures

    private static FullAnalysis fullAnalysis(List<Insight> insights) {
        return fullAnalysis(insights, 1L);
    }

    private static FullAnalysis fullAnalysis(List<Insight> insights, long scanTotal) {
        return fullAnalysis(insights, scanTotal, List.of());
    }

    private static FullAnalysis fullAnalysis(List<Insight> insights, long scanTotal,
            List<tn.finrisk.aiservice.client.dto.FactorView> factors) {
        TransactionView focal = new TransactionView(100L, 7L, null, new BigDecimal("12500"),
                "TND", "TRANSFER", "TN", T, "ONLINE", "41.226.10.8", "DEV-7734",
                "SARL MedTech", null, "COMPLETED", T);
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, 100L, new BigDecimal("87"),
                "HIGH", "HOLD", T, "v1", factors);
        CustomerView customer = new CustomerView(42L, "Amina", "Ben Salah", "a@e.com",
                OffsetDateTime.parse("2000-01-01T00:00:00Z").toLocalDate(), "TN", "Tunis",
                "INDIVIDUAL", "ACTIVE", T);
        AccountView acc = new AccountView(7L, "TN-001", "CHECKING", "TND",
                new BigDecimal("50000"), T.toLocalDate(), "ACTIVE");
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, focal, customer,
                List.of(acc), List.of(focal), List.of(focal), scanTotal);
        return new FullAnalysis(ctx, insights, null);
    }

    private Graph graph(FullAnalysis fa) {
        return graphService.build(fa);
    }

    // --------------------------------------------------------- parsing LLM

    @Test
    void structuredChecksUseOnlyServerSideEvidence() {
        var reference = new ConstellationNarrative.EvidenceReference("Appareil commun", "TX #200", "edge:1");
        var catalog = java.util.Map.of("E1", reference);
        String answer = """
                {"summary":"Lecture.","llmScore":21.5,"checks":[
                  {"fact":"Même appareil","hypothesis":"Usage partagé","alternative":"Poste commun",
                   "verification":"Consulter les connexions","references":["E1"]},
                  {"fact":"Fait inventé","hypothesis":"Hypothèse","alternative":"Alternative",
                   "verification":"Vérifier","references":["E99"]}
                ]}
                """;
        var parsed = ConstellationNarrativeService.tryParse(answer, Set.of(), catalog);
        assertNotNull(parsed);
        assertEquals(1, parsed.checks().size());
        assertEquals(List.of(reference), parsed.checks().getFirst().evidence());
        assertNull(parsed.llmScore());
        assertNull(ConstellationNarrativeService.tryParse(answer.replace("\"E1\"", "\"E77\""),
                Set.of(), catalog));
    }

    @Test
    void tryParseAcceptsCleanJson() {
        String json = """
                {"summary":"Réseau suspect détecté.","hypotheses":["Structuration"],
                "recommendedActions":["Vérifier KYC"],"focusNodes":["ben:X"]}""";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(json, Set.of("ben:X"));
        assertNotNull(n);
        assertEquals("Réseau suspect détecté.", n.summary());
        assertEquals(1, n.hypotheses().size());
        assertEquals(1, n.recommendedActions().size());
        assertEquals(List.of("ben:X"), n.focusNodes());
    }

    @Test
    void tryParseDropsFocusNodesThatDoNotExistInTheGraph() {
        String json = """
                {"summary":"Lecture.","hypotheses":["H1"],
                "recommendedActions":["A1"],"focusNodes":["ben:inventé","acc:7","zzz"]}""";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(
                json, Set.of("acc:7", "ben:SARL MedTech"));

        assertNotNull(n);
        assertEquals(List.of("acc:7"), n.focusNodes(),
                "un identifiant inventé par le LLM n'est jamais affiché");
    }

    @Test
    void tryParseWithNoValidNodeIdsYieldsEmptyFocus() {
        String json = """
                {"summary":"Lecture.","hypotheses":["H"],"recommendedActions":["A"],
                "focusNodes":["ben:X"]}""";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(json, Set.of());
        assertNotNull(n);
        assertTrue(n.focusNodes().isEmpty());
    }

    @Test
    void tryParseStripsMarkdownFences() {
        String fenced = """
                ```json
                {"summary":"Lecture du graphe.","hypotheses":["H1"],
                "recommendedActions":["A1"],"focusNodes":[]}
                ```""";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(fenced, Set.of());
        assertNotNull(n);
        assertEquals("Lecture du graphe.", n.summary());
    }

    @Test
    void tryParseExtractsJsonSurroundedByText() {
        String noisy = "Voici l'analyse :\n{\"summary\":\"Bon résumé.\",\"hypotheses\":[],"
                + "\"recommendedActions\":[\"Action\"],\"focusNodes\":[]}\nMerci.";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(noisy, Set.of());
        assertNotNull(n);
        assertEquals("Bon résumé.", n.summary());
    }

    @Test
    void tryParseRejectsNullOrBlank() {
        assertNull(ConstellationNarrativeService.tryParse(null, Set.of()));
        assertNull(ConstellationNarrativeService.tryParse(" ", Set.of()));
    }

    @Test
    void tryParseRejectsJsonWithoutSummary() {
        assertNull(ConstellationNarrativeService.tryParse("{\"hypotheses\":[\"x\"]}", Set.of()));
    }

    @Test
    void tryParseRejectsJsonWithoutHypothesesNorActions() {
        assertNull(ConstellationNarrativeService.tryParse(
                "{\"summary\":\"S\",\"hypotheses\":[],\"recommendedActions\":[]}", Set.of()));
    }

    @Test
    void tryParseRejectsBrokenJson() {
        assertNull(ConstellationNarrativeService.tryParse("{\"summary\": ", Set.of()));
    }

    @Test
    void tryParseRejectsPlainProse() {
        assertNull(ConstellationNarrativeService.tryParse("Le graphe montre un réseau.", Set.of()));
    }

    // ------------------------------------------------- avis autonome du LLM

    @Test
    void tryParseAcceptsLlmScoreAndRationale() {
        String json = """
                {"summary":"Réseau.","hypotheses":["H"],"recommendedActions":["A"],
                "focusNodes":[],"llmScore":62,"llmScoreRationale":"Signaux partagés sérieux."}""";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(json, Set.of());
        assertNotNull(n);
        assertEquals(62, n.llmScore());
        assertEquals("Signaux partagés sérieux.", n.llmScoreRationale());
    }

    @Test
    void tryParseRejectsOutOfBoundsLlmScore() {
        String json = """
                {"summary":"Réseau.","hypotheses":["H"],"recommendedActions":["A"],
                "focusNodes":[],"llmScore":150,"llmScoreRationale":"x"}""";
        ConstellationNarrative n = ConstellationNarrativeService.tryParse(json, Set.of());
        assertNotNull(n);
        assertNull(n.llmScore(), "un score hors bornes n'est jamais affiché");
    }

    @Test
    void deterministicNarrativeNeverHasLlmScore() {
        FullAnalysis fa = fullAnalysis(List.of());
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(
                fa, graph(fa));
        assertNull(n.llmScore(), "sans LLM, aucun avis autonome n'est inventé");
    }

    // ------------------------------------- neutralité + facteurs du moteur

    @Test
    void systemPromptDemandsNeutralityAndOwnScore() {
        String p = ConstellationNarrativeService.SYSTEM_PROMPT;
        assertTrue(p.contains("NEUTRALITÉ"), "la neutralité est la règle n°1 du prompt");
        assertTrue(p.contains("LÉGITIMES"), "le prompt rappelle que la majorité est normale");
        assertTrue(p.contains("llmScore"), "le LLM produit son propre score");
        assertTrue(p.contains("POIDS"), "le prompt parle des poids des règles");
    }

    @Test
    void userPromptIncludesEngineFactorsWithWeights() {
        var factor = new tn.finrisk.aiservice.client.dto.FactorView(1L, "RULE-012",
                "Structuring", "PATTERN", new BigDecimal("25"), new BigDecimal("1.0"),
                "3 opérations sous le seuil en 24h");
        FullAnalysis fa = fullAnalysis(List.of(), 1L, List.of(factor));
        String prompt = ConstellationNarrativeService.buildUserPrompt(fa, graph(fa));
        assertTrue(prompt.contains("FACTEURS DU SCORE MOTEUR"), prompt);
        assertTrue(prompt.contains("RULE-012"), prompt);
        assertTrue(prompt.contains("+25"), prompt);
        assertTrue(prompt.contains("poids 1.0"), prompt);
    }

    // -------------------------------------------- focus : priorité et sens

    @Test
    void deterministicFocusStartsWithInvestigationCenters() {
        FullAnalysis fa = fullAnalysis(List.of());
        Graph g = graph(fa);
        List<String> focus = ConstellationNarrativeService.deterministicFocus(g);

        ConstellationNode first = g.nodes().stream()
                .filter(n -> n.id().equals(focus.get(0))).findFirst().orElseThrow();
        assertTrue(first.investigationFocus(),
                "le premier focus doit être un CENTRE d'investigation : " + focus);
        for (String id : focus) {
            ConstellationNode n = g.nodes().stream()
                    .filter(node -> node.id().equals(id)).findFirst().orElseThrow();
            assertTrue(n.investigationFocus() || "HIGH".equals(n.risk()),
                    "un focus doit être une focale ou un risque étayé : " + id);
        }
    }

    @Test
    void highRiskSharedEntityIsStillAFocus() {
        FullAnalysis fa = fullAnalysis(List.of());
        TransactionView focal = new TransactionView(100L, 7L, null, new BigDecimal("12500"),
                "TND", "TRANSFER", "TN", T, "ONLINE", "41.226.10.8", "DEV-7734",
                "SARL MedTech", null, "COMPLETED", T);
        TransactionView other1 = new TransactionView(200L, 55L, null, new BigDecimal("9000"),
                "TND", "TRANSFER", "TN", T, "ONLINE", "2.2.2.2", "dX",
                "SARL MedTech", null, "COMPLETED", T);
        TransactionView other2 = new TransactionView(201L, 61L, null, new BigDecimal("8000"),
                "TND", "TRANSFER", "TN", T, "ONLINE", "3.3.3.3", "dY",
                "SARL MedTech", null, "COMPLETED", T);
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, 100L, new BigDecimal("87"),
                "HIGH", "HOLD", T, "v1", List.of());
        CustomerView customer = new CustomerView(42L, "Amina", "Ben Salah", "a@e.com",
                OffsetDateTime.parse("2000-01-01T00:00:00Z").toLocalDate(), "TN", "Tunis",
                "INDIVIDUAL", "ACTIVE", T);
        AccountView acc = new AccountView(7L, "TN-001", "CHECKING", "TND",
                new BigDecimal("50000"), T.toLocalDate(), "ACTIVE");
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, focal, customer,
                List.of(acc), List.of(focal), List.of(focal, other1, other2), 3);
        FullAnalysis withNetwork = new FullAnalysis(ctx, List.<Insight>of(), null);
        Graph g = graph(withNetwork);

        assertTrue(g.nodes().stream().anyMatch(n -> "ben:SARL MedTech".equals(n.id())
                && "HIGH".equals(n.risk())));
        List<String> focus = ConstellationNarrativeService.deterministicFocus(g);
        assertTrue(focus.contains("ben:SARL MedTech"),
                "un bénéficiaire HIGH doit rester un point de focus : " + focus);
    }

    // ------------------------------------------------- qualité des données

    @Test
    void dataQualityIsCompleteWhenEverythingIsThere() {
        FullAnalysis fa = fullAnalysis(List.of());
        assertEquals(ConstellationGraphService.QUALITY_COMPLETE,
                ConstellationNarrativeService.dataQuality(fa, graph(fa)));
    }

    @Test
    void dataQualityIsPartialWhenScanIsTruncated() {
        FullAnalysis fa = fullAnalysis(List.of(), 1000);
        assertEquals(ConstellationGraphService.QUALITY_PARTIAL,
                ConstellationNarrativeService.dataQuality(fa, graph(fa)));
    }

    @Test
    void deterministicNarrativeReportsPartialQualityWhenScanTruncated() {
        FullAnalysis fa = fullAnalysis(List.of(), 1000);
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(fa, graph(fa));
        assertEquals(ConstellationGraphService.QUALITY_PARTIAL, n.dataQuality());
        assertTrue(n.summary().toLowerCase(java.util.Locale.ROOT).contains("partiel"),
                "le récit doit DIRE que le scan est incomplet : " + n.summary());
    }

    @Test
    void deterministicNarrativeWarnsWhenSharedAccountsAreCut() {
        TransactionView focal = new TransactionView(100L, 7L, null, new BigDecimal("12500"),
                "TND", "TRANSFER", "TN", T, "ONLINE", "41.226.10.8", "DEV-7734",
                "SARL MedTech", null, "COMPLETED", T);
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, 100L, new BigDecimal("87"),
                "HIGH", "HOLD", T, "v1", List.of());
        CustomerView customer = new CustomerView(42L, "Amina", "Ben Salah", "a@e.com",
                OffsetDateTime.parse("2000-01-01T00:00:00Z").toLocalDate(), "TN", "Tunis",
                "INDIVIDUAL", "ACTIVE", T);
        AccountView acc = new AccountView(7L, "TN-001", "CHECKING", "TND",
                new BigDecimal("50000"), T.toLocalDate(), "ACTIVE");
        // Les 10 autres comptes partagent le MÊME device que la focale.
        List<TransactionView> sharedScan = new java.util.ArrayList<>();
        sharedScan.add(focal);
        for (long a = 50; a < 60; a++) {
            sharedScan.add(new TransactionView(200 + a, a, null, BigDecimal.TEN, "TND",
                    "TRANSFER", "TN", T, "ONLINE", "1.1.1." + a, "DEV-7734", "BEN-" + a,
                    null, "COMPLETED", T));
        }
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, focal, customer,
                List.of(acc), List.of(focal), sharedScan, sharedScan.size());
        Graph g = graph(new FullAnalysis(ctx, List.<Insight>of(), null));

        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(
                new FullAnalysis(ctx, List.<Insight>of(), null), g);
        assertTrue(g.truncated());
        assertTrue(n.summary().contains("écartés"),
                "le récit doit DIRE ce qui a été coupé : " + n.summary());
    }

    @Test
    void dataQualityIsDegradedWithoutTransaction() {
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, 99L, BigDecimal.ZERO, "LOW",
                "APPROVED", T, "v1", List.of());
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, null, null,
                List.of(), List.of(), List.of(), 0);
        FullAnalysis fa = new FullAnalysis(ctx, List.<Insight>of(), null);

        assertEquals(ConstellationGraphService.QUALITY_DEGRADED,
                ConstellationNarrativeService.dataQuality(fa, graph(fa)));
    }

    // --------------------------------------------------- repli déterministe

    @Test
    void deterministicNarrativeIsInFrenchAndComplete() {
        FullAnalysis fa = fullAnalysis(List.of(
                new Insight(InsightCode.BENEFICIARY_NETWORK, InsightSeverity.HIGH,
                        "Bénéficiaire sur 2 autres comptes.", "[graph] otherAccounts=[55, 61]"),
                new Insight(InsightCode.DEVICE_SHARED, InsightSeverity.HIGH,
                        "Device partagé.", "[graph] device=DEV-7734")));
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(
                fa, graph(fa));

        assertNotNull(n.summary());
        assertFalse(n.summary().isBlank());
        assertFalse(n.hypotheses().isEmpty(), "au moins une hypothèse en français");
        assertFalse(n.recommendedActions().isEmpty(), "au moins une action");
        assertFalse(n.focusNodes().isEmpty(), "au moins un nœud focus");
        assertTrue(n.hypotheses().stream().anyMatch(h -> h.contains("bénéficiaire")
                        || h.contains("appareil") || h.contains("Structuration")
                        || h.contains("réseau")),
                "hypothèses en français attendues : " + n.hypotheses());
    }

    @Test
    void deterministicNarrativeHasHypothesisForEachInsightCode() {
        FullAnalysis fa = fullAnalysis(List.of(
                new Insight(InsightCode.BENEFICIARY_NETWORK, InsightSeverity.HIGH, "m", "e"),
                new Insight(InsightCode.DEVICE_SHARED, InsightSeverity.HIGH, "m", "e"),
                new Insight(InsightCode.IP_SHARED, InsightSeverity.MEDIUM, "m", "e"),
                new Insight(InsightCode.AMOUNT_DEVIATION, InsightSeverity.HIGH, "m", "e"),
                new Insight(InsightCode.VELOCITY_SPIKE, InsightSeverity.HIGH, "m", "e"),
                new Insight(InsightCode.NEW_BENEFICIARY, InsightSeverity.MEDIUM, "m", "e"),
                new Insight(InsightCode.GEO_ANOMALY, InsightSeverity.MEDIUM, "m", "e")));
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(
                fa, graph(fa));
        assertTrue(n.hypotheses().size() >= 7,
                "une hypothèse par code d'insight, obtenue " + n.hypotheses().size());
    }

    @Test
    void deterministicNarrativeWithoutInsightsStillProducesNarrative() {
        FullAnalysis fa = fullAnalysis(List.of());
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(
                fa, graph(fa));
        assertFalse(n.summary().isBlank());
        assertFalse(n.hypotheses().isEmpty());
        assertFalse(n.recommendedActions().isEmpty());
    }

    @Test
    void deterministicNarrativeNeverStatesAFactAsEstablished() {
        FullAnalysis fa = fullAnalysis(List.of(
                new Insight(InsightCode.DEVICE_SHARED, InsightSeverity.HIGH, "m", "e")));
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(fa, graph(fa));
        String deviceHypothesis = n.hypotheses().stream()
                .filter(h -> h.contains("appareil")).findFirst().orElseThrow();
        assertTrue(deviceHypothesis.contains("signal") || deviceHypothesis.contains("preuve"),
                "l'appareil partagé doit être présenté comme un signal : " + deviceHypothesis);
        assertFalse(deviceHypothesis.contains("prouve"), deviceHypothesis);
    }

    @Test
    void narrateFallsBackToDeterministicWhenLlmDisabled() {
        LlmClient llm = mock(LlmClient.class);
        ConstellationNarrativeService service = new ConstellationNarrativeService(llm);
        FullAnalysis fa = fullAnalysis(List.of());
        var narration = service.narrate(fa, graph(fa));
        assertEquals("DETERMINISTE", narration.mode());
        assertNotNull(narration.narrative());
    }

    @Test
    void focusNodesAreInvestigationCentersOrHighRiskNodes() {
        FullAnalysis fa = fullAnalysis(List.of());
        Graph g = graph(fa);
        ConstellationNarrative n = ConstellationNarrativeService.deterministicNarrative(fa, g);
        for (String focusId : n.focusNodes()) {
            assertTrue(g.nodes().stream().anyMatch(node -> node.id().equals(focusId)
                            && (node.investigationFocus() || "HIGH".equals(node.risk()))),
                    "focus doit être une focale ou un nœud HIGH : " + focusId);
        }
    }

    // ------------------------------------------------------------- prompt

    @Test
    void userPromptContainsEntityTypesAndEvidence() {
        FullAnalysis fa = fullAnalysis(List.of());
        Graph g = graph(fa);
        String prompt = ConstellationNarrativeService.buildUserPrompt(fa, g);
        assertTrue(prompt.contains("TRANSACTION FOCALE"));
        assertTrue(prompt.contains("SARL MedTech"));
        assertTrue(prompt.contains("GRAPHE"));
        assertTrue(prompt.contains("Arêtes"));
    }

    @Test
    void systemPromptSeparatesInvestigationFocusFromRisk() {
        assertTrue(ConstellationNarrativeService.SYSTEM_PROMPT.contains("investigationFocus"));
        assertTrue(ConstellationNarrativeService.SYSTEM_PROMPT.contains("pas une preuve"));
        assertTrue(ConstellationNarrativeService.SYSTEM_PROMPT.contains("POSSIBILITÉS À VÉRIFIER"));
    }
}
