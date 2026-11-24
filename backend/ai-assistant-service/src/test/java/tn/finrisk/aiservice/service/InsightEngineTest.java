package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.dto.AnalysisContext;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Tests du moteur PUR : les seuils de l'InsightEngine sont vérifiés un par un.
 * Aucun service distant — c'est le gain d'avoir séparé calcul et orchestration.
 */
class InsightEngineTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-06T14:00:00Z");
    private final InsightEngine engine = new InsightEngine();

    // ------------------------------------------------------------ fixtures

    private static TransactionView tx(long id, long accountId, String amount, OffsetDateTime at,
            String beneficiary, String country, String device, String ip) {
        return new TransactionView(id, accountId, null, new BigDecimal(amount), "EUR",
                "TRANSFER", country, at, "ONLINE", ip, device, beneficiary, "COMPLETED", at);
    }

    private static TransactionView defaultTx(long id, long accountId, String amount) {
        return tx(id, accountId, amount, T, "SCAM SARL", "FR", "dev-1", "10.0.0.1");
    }

    private static CustomerView customer(String country) {
        return new CustomerView(7L, "Amina", "Ben Salah", "amina@example.com",
                OffsetDateTime.parse("2000-01-01T00:00:00Z").toLocalDate(), country, "Tunis",
                "INDIVIDUAL", "ACTIVE", T);
    }

    private static AnalysisContext ctx(TransactionView current, List<TransactionView> history,
            List<TransactionView> scan, CustomerView customer) {
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, current.transactionId(),
                new BigDecimal("87"), "HIGH", "HOLD", T, "v1",
                List.of());
        AccountView account = new AccountView(current.accountId(), "TN-001", "CHECKING",
                "EUR", new BigDecimal("1000"), T.toLocalDate(), "ACTIVE");
        return new AnalysisContext(alert, evaluation, current, customer,
                List.of(account), history, scan);
    }

    private static Optional<Insight> byCode(List<Insight> list, InsightCode code) {
        return list.stream().filter(i -> i.code() == code).findFirst();
    }

    private static boolean has(List<Insight> list, InsightCode code) {
        return byCode(list, code).isPresent();
    }

    // ------------------------------------------------------------- statistiques

    @Test
    void amountDeviationHigh_whenAmountIsAbove3xP95() {
        // historique : 90,100,110,120,130 -> P95 = 130 ; transaction = 500 -> ratio 3.8
        List<TransactionView> history = List.of(
                tx(10, 7, "90", T.minusDays(30), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "100", T.minusDays(20), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "110", T.minusDays(10), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "120", T.minusDays(5), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(14, 7, "130", T.minusDays(1), "ALICE", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "500", T, "SCAM SARL", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        Insight insight = byCode(out, InsightCode.AMOUNT_DEVIATION).orElseThrow();
        assertEquals(InsightSeverity.HIGH, insight.severity());
        assertTrue(insight.evidence().contains("p95=130"));
        assertTrue(insight.evidence().contains("ratio=3.8"));
    }

    @Test
    void amountDeviationAbsent_whenAmountWithinHabit() {
        List<TransactionView> history = List.of(
                tx(10, 7, "90", T.minusDays(30), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "100", T.minusDays(20), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "110", T.minusDays(10), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "120", T.minusDays(5), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(14, 7, "130", T.minusDays(1), "ALICE", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "110", T, "ALICE", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        assertFalse(has(out, InsightCode.AMOUNT_DEVIATION));
    }

    @Test
    void insufficientData_whenHistoryShort() {
        List<TransactionView> history = List.of(
                tx(10, 7, "90", T.minusDays(1), "ALICE", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "500", T, "SCAM SARL", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        assertTrue(has(out, InsightCode.INSUFFICIENT_DATA),
                "le manque de données doit être DIT, pas tu");
        assertFalse(has(out, InsightCode.AMOUNT_DEVIATION),
                "pas de stats sur 1 seule observation");
        assertFalse(has(out, InsightCode.NEW_BENEFICIARY),
                "« premier virement » meaningless sans historique");
    }

    // -------------------------------------------------------------- temporel

    @Test
    void velocityHigh_whenFourOpsInOneHour() {
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusMinutes(50), "X", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "10", T.minusMinutes(30), "X", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "10", T.minusMinutes(10), "X", "FR", "d1", "1.1.1.1"),
                tx(99, 7, "10", T, "X", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(20), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(14, 7, "10", T.minusDays(21), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(15, 7, "10", T.minusDays(22), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(16, 7, "10", T.minusDays(23), "ALICE", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "10", T, "X", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        Insight insight = byCode(out, InsightCode.VELOCITY_SPIKE).orElseThrow();
        assertEquals(InsightSeverity.HIGH, insight.severity());
        assertTrue(insight.evidence().contains("window=4/60min"));
    }

    @Test
    void velocityAbsent_whenOnlyOneOpInWindow() {
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusDays(30), "X", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "10", T.minusDays(29), "X", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "10", T.minusDays(28), "X", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(27), "X", "FR", "d1", "1.1.1.1"),
                tx(99, 7, "10", T, "X", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "10", T, "X", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        assertFalse(has(out, InsightCode.VELOCITY_SPIKE));
    }

    // ----------------------------------------------------------------- graph

    @Test
    void beneficiaryNetworkHigh_whenSharedByTwoOtherAccounts() {
        TransactionView current = tx(99, 7, "500", T, "SCAM SARL", "FR", "d1", "1.1.1.1");
        List<TransactionView> scan = List.of(
                current,
                tx(200, 42, "300", T.minusDays(2), "scam sarl", "FR", "d2", "2.2.2.2"),
                tx(201, 43, "300", T.minusDays(3), "SCAM SARL", "FR", "d3", "3.3.3.3"));
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusDays(10), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "10", T.minusDays(9), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "10", T.minusDays(8), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(7), "ALICE", "FR", "d1", "1.1.1.1"),
                current);

        List<Insight> out = engine.analyze(ctx(current, history, scan, customer("FR")));

        Insight insight = byCode(out, InsightCode.BENEFICIARY_NETWORK).orElseThrow();
        assertEquals(InsightSeverity.HIGH, insight.severity());
        assertTrue(insight.evidence().contains("otherAccounts=[42, 43]")
                        || insight.evidence().contains("otherAccounts=[43, 42]"),
                "la preuve doit nommer les comptes : " + insight.evidence());
    }

    @Test
    void deviceSharedMedium_whenOneOtherAccountUsedSameDevice() {
        TransactionView current = tx(99, 7, "500", T, "ALICE", "FR", "dev-9", "1.1.1.1");
        List<TransactionView> scan = List.of(
                current,
                tx(300, 55, "200", T.minusDays(4), "BOB", "FR", "dev-9", "9.9.9.9"));
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusDays(10), "ALICE", "FR", "dev-9", "1.1.1.1"),
                tx(11, 7, "10", T.minusDays(9), "ALICE", "FR", "dev-9", "1.1.1.1"),
                tx(12, 7, "10", T.minusDays(8), "ALICE", "FR", "dev-9", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(7), "ALICE", "FR", "dev-9", "1.1.1.1"),
                current);

        List<Insight> out = engine.analyze(ctx(current, history, scan, customer("FR")));

        Insight insight = byCode(out, InsightCode.DEVICE_SHARED).orElseThrow();
        assertEquals(InsightSeverity.MEDIUM, insight.severity());
    }

    // -------------------------------------------------------------- contexte

    @Test
    void newBeneficiaryMedium_whenNeverSeenInHistory() {
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusDays(10), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "10", T.minusDays(9), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "10", T.minusDays(8), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(7), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(99, 7, "10", T, "SCAM SARL", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "10", T, "SCAM SARL", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        assertTrue(has(out, InsightCode.NEW_BENEFICIARY));
    }

    @Test
    void newBeneficiaryAbsent_whenSeenBeforeIgnoringCase() {
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusDays(10), "scam sarl", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "10", T.minusDays(9), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "10", T.minusDays(8), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(7), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(99, 7, "10", T, "SCAM SARL", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "10", T, "SCAM SARL", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("FR")));

        assertFalse(has(out, InsightCode.NEW_BENEFICIARY),
                "mêmes libellés à la casse près = pas un premier virement");
    }

    @Test
    void geoAnomalyMedium_whenCountryDiffers() {
        List<TransactionView> history = List.of(
                tx(10, 7, "10", T.minusDays(10), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(11, 7, "10", T.minusDays(9), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(12, 7, "10", T.minusDays(8), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(13, 7, "10", T.minusDays(7), "ALICE", "TN", "d1", "1.1.1.1"),
                tx(99, 7, "10", T, "ALICE", "FR", "d1", "1.1.1.1"));
        TransactionView current = tx(99, 7, "10", T, "ALICE", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("TN")));

        Insight insight = byCode(out, InsightCode.GEO_ANOMALY).orElseThrow();
        assertEquals(InsightSeverity.MEDIUM, insight.severity());
    }

    @Test
    void insightsSortedHighFirst() {
        List<TransactionView> history = List.of(
                tx(10, 7, "90", T.minusDays(30), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(11, 7, "100", T.minusDays(20), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(12, 7, "110", T.minusDays(10), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(13, 7, "120", T.minusDays(5), "ALICE", "FR", "d1", "1.1.1.1"),
                tx(14, 7, "130", T.minusDays(1), "ALICE", "FR", "d1", "1.1.1.1"));
        // amount 500 -> HIGH ; pays FR vs TN -> MEDIUM
        TransactionView current = tx(99, 7, "500", T, "SCAM SARL", "FR", "d1", "1.1.1.1");

        List<Insight> out = engine.analyze(ctx(current, history, history, customer("TN")));

        assertFalse(out.isEmpty());
        assertEquals(InsightSeverity.HIGH, out.getFirst().severity(),
                "l'analyste doit voir les signaux forts en premier");
        boolean mediumSeen = false;
        for (Insight i : out) {
            if (i.severity() == InsightSeverity.MEDIUM) {
                mediumSeen = true;
            } else if (i.severity() == InsightSeverity.LOW && mediumSeen) {
                assertTrue(false, "tri incorrect : LOW avant MEDIUM");
            }
        }
    }
}
