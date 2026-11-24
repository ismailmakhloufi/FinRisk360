package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.FactorView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.service.dto.AnalysisContext;
import tn.finrisk.aiservice.web.dto.AnalysisResponse;
import tn.finrisk.aiservice.web.dto.AiVerdict;
import tn.finrisk.aiservice.web.dto.ChatIntent;
import tn.finrisk.aiservice.web.dto.Insight;

/**
 * Le chat déterministe : chaque intention majeure testée sur un contexte
 * RÉEL généré par l'InsightEngine (pas des insights factices) — ce que le
 * routeur répond, c'est ce qu'il répondra en démo.
 */
class ChatRouterTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-06T14:00:00Z");
    private final InsightEngine engine = new InsightEngine();
    private final ChatRouter router = new ChatRouter();

    private static TransactionView tx(long id, long accountId, String amount, OffsetDateTime at,
            String beneficiary, String country, String device, String ip) {
        return new TransactionView(id, accountId, null, new BigDecimal(amount), "EUR",
                "TRANSFER", country, at, "ONLINE", ip, device, beneficiary, "COMPLETED", at);
    }

    /** Contexte fidèle à la démo : P95=130, tx=500, pays TN vs FR, bénéficiaire inédit. */
    private FullAnalysis fixture() {
        List<TransactionView> history = List.of(
                tx(10, 7, "90", T.minusDays(30), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(11, 7, "100", T.minusDays(20), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(12, 7, "110", T.minusDays(10), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(13, 7, "120", T.minusDays(5), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(14, 7, "130", T.minusDays(1), "ALICE", "TN", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "500", T, "SCAM SARL", "FR", "d1", "1.1.1.1");
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, 99L, new BigDecimal("87"),
                "HIGH", "HOLD", T, "v1", List.of(
                        new FactorView(1L, "R-AMOUNT", "Montant eleve", "THRESHOLD",
                                new BigDecimal("30.00"), BigDecimal.ONE, "seuil depasse")));
        CustomerView customer = new CustomerView(7L, "Amina", "Ben Salah", "amina@example.com",
                OffsetDateTime.parse("1990-01-01T00:00:00Z").toLocalDate(), "TN", "Tunis",
                "INDIVIDUAL", "ACTIVE", T);
        AccountView account = new AccountView(7L, "TN-001", "CHECKING", "EUR",
                new BigDecimal("1000"), T.toLocalDate(), "ACTIVE");

        AnalysisContext context = new AnalysisContext(alert, evaluation, current, customer,
                List.of(account), history, history);
        List<Insight> insights = engine.analyze(context);
        AnalysisResponse response = new AnalysisResponse(1L, 1L, new BigDecimal("87"),
                "HIGH", "HOLD", AiVerdict.FRAUDE_SUSPECTE, 0.8, insights, "explication", T);
        return new FullAnalysis(context, insights, response);
    }

    private ChatRouter.RoutedReply ask(String message) {
        return router.route(fixture(), message);
    }

    private void assertIntent(ChatIntent expected, String message) {
        ChatRouter.RoutedReply reply = ask(message);
        assertEquals(expected, reply.intent(), "message: " + message);
        assertTrue(!reply.answer().isBlank(), "réponse vide pour: " + message);
    }

    // ------------------------------------------------------------ intentions

    @Test
    void summary_whenAskedToResume() {
        ChatRouter.RoutedReply reply = ask("résume l'alerte");
        assertEquals(ChatIntent.SUMMARY, reply.intent());
        assertTrue(reply.answer().contains("ALT-1"), reply.answer());
        assertTrue(reply.answer().contains("87"), reply.answer());
        assertTrue(reply.answer().contains("SCAM SARL"), reply.answer());
    }

    @Test
    void whyScore_whenAskedWhy() {
        ChatRouter.RoutedReply reply = ask("pourquoi ce score ?");
        assertEquals(ChatIntent.WHY_SCORE, reply.intent());
        assertTrue(reply.answer().contains("Montant eleve"), reply.answer());
    }

    @Test
    void signals_whenAskedForSignals() {
        ChatRouter.RoutedReply reply = ask("quels signaux ?");
        assertEquals(ChatIntent.SIGNALS, reply.intent());
        assertTrue(reply.answer().contains("[HIGH]"), reply.answer());
        assertTrue(reply.answer().contains("[transactions]") || reply.answer().contains("[graph]")
                || reply.answer().contains("[customers]"), reply.answer());
    }

    @Test
    void verdict_whenAskedIfFraud() {
        ChatRouter.RoutedReply reply = ask("est-ce une fraude ?");
        assertEquals(ChatIntent.VERDICT, reply.intent());
        assertTrue(reply.answer().contains("FRAUDE_SUSPECTE"), reply.answer());
    }

    @Test
    void beneficiary_withOccurrences() {
        ChatRouter.RoutedReply reply = ask("qui est le bénéficiaire ?");
        assertEquals(ChatIntent.BENEFICIAIRE, reply.intent());
        assertTrue(reply.answer().contains("SCAM SARL"), reply.answer());
        assertTrue(reply.answer().contains("0 occurrence"), reply.answer());
    }

    @Test
    void amount_comparedToP95() {
        ChatRouter.RoutedReply reply = ask("le montant est-il normal ?");
        assertEquals(ChatIntent.AMOUNT, reply.intent());
        assertTrue(reply.answer().contains("P95"), reply.answer());
        assertTrue(reply.answer().contains("130"), reply.answer());
    }

    @Test
    void geo_detectsIncoherence() {
        ChatRouter.RoutedReply reply = ask("quel est le pays ?");
        assertEquals(ChatIntent.GEO, reply.intent());
        assertTrue(reply.answer().contains("FR"), reply.answer());
        assertTrue(reply.answer().contains("incohérent"), reply.answer());
    }

    @Test
    void actions_neverDecides() {
        ChatRouter.RoutedReply reply = ask("que faire maintenant ?");
        assertEquals(ChatIntent.ACTIONS, reply.intent());
        assertTrue(reply.answer().contains("Je ne décide pas"), reply.answer());
    }

    @Test
    void confidence_explainsTheFormula() {
        ChatRouter.RoutedReply reply = ask("ta confiance ?");
        assertEquals(ChatIntent.CONFIDENCE, reply.intent());
        assertTrue(reply.answer().contains("0.8"), reply.answer());
        assertTrue(reply.answer().contains("plafond 0.95"), reply.answer());
    }

    @Test
    void factors_listsPositiveOnes() {
        ChatRouter.RoutedReply reply = ask("quelles règles ont joué ?");
        assertEquals(ChatIntent.FACTORS, reply.intent());
        assertTrue(reply.answer().contains("Montant eleve"), reply.answer());
    }

    // --------------------------------------------------- robustesse du chat

    @Test
    void help_whenAskedAide() {
        assertIntent(ChatIntent.HELP, "aide");
    }

    @Test
    void fallback_whenQuestionOutOfScope() {
        ChatRouter.RoutedReply reply = ask("quel temps fait-il à Tunis ?");
        assertEquals(ChatIntent.FALLBACK, reply.intent());
        assertTrue(reply.answer().contains("pas reconnu"), reply.answer());
        assertTrue(reply.answer().contains("résume"), reply.answer());
    }

    @Test
    void greeting_whenShortHello() {
        assertIntent(ChatIntent.GREETING, "bonjour");
    }

    @Test
    void normalize_stripsAccentsAndCase() {
        assertEquals("resume ce score", ChatRouter.normalize("RéSumé CE Score"));
        assertEquals("beneficiaire ?", ChatRouter.normalize("bénéficiaire ?"));
    }

    @Test
    void whyScoreWinsOverScore_whenGeneralWhy() {
        // « pourquoi » + « score » : c'est le raisonnement qu'on veut, pas le chiffre nu
        assertIntent(ChatIntent.WHY_SCORE, "d'où vient le score ?");
    }

    // ------------------------------------------ constellation + explication

    @Test
    void constellation_whenAskedForTheGraph() {
        FullAnalysis fa = fixture();
        var graph = new ConstellationGraphService().build(fa);
        ChatRouter.RoutedReply reply = router.route(fa, graph, "montre la constellation");
        assertEquals(ChatIntent.CONSTELLATION, reply.intent());
        assertTrue(reply.answer().contains("entités"), reply.answer());
        assertTrue(reply.answer().contains("/constellation/1"), reply.answer());
        assertTrue(reply.answer().contains("relations vérifiables"), reply.answer());
    }

    @Test
    void constellation_withoutGraphStillGivesTheLink() {
        ChatRouter.RoutedReply reply = ask("constellation de l'alerte");
        assertEquals(ChatIntent.CONSTELLATION, reply.intent());
        assertTrue(reply.answer().contains("/constellation/1"), reply.answer());
    }

    @Test
    void explainAlert_whenAnalystDoesNotUnderstand() {
        ChatRouter.RoutedReply reply = ask("explique-moi cette alerte");
        assertEquals(ChatIntent.EXPLAIN_ALERT, reply.intent());
        assertTrue(reply.answer().contains("ALT-1"), reply.answer());
        assertTrue(reply.answer().contains("Montant eleve"), reply.answer());
        assertTrue(reply.answer().contains("poids"), reply.answer());
        assertTrue(reply.answer().contains("/constellation/1"), reply.answer());
    }

    @Test
    void explainAlert_neverSaysLaundering() {
        ChatRouter.RoutedReply reply = ask("je ne comprends pas cette alerte");
        assertEquals(ChatIntent.EXPLAIN_ALERT, reply.intent());
        assertFalse(reply.answer().toLowerCase().contains("blanchiment"),
                "l'assistant n'affirme jamais qu'une transaction est du blanchiment : "
                        + reply.answer());
    }
}
