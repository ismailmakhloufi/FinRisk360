package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.service.ConstellationGraphService.Graph;
import tn.finrisk.aiservice.service.dto.AnalysisContext;
import tn.finrisk.aiservice.web.dto.ConstellationEdge;
import tn.finrisk.aiservice.web.dto.ConstellationNode;
import tn.finrisk.aiservice.web.dto.Insight;

/**
 * Tests PURS du constructeur de graphe : chaque nœud et chaque arête doit
 * naître d'une donnée réelle, avec les bonnes bornes et le bon risque —
 * surtout : le CENTRE d'investigation n'est pas un niveau de risque.
 */
class ConstellationGraphServiceTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-06T14:00:00Z");
    private final ConstellationGraphService service = new ConstellationGraphService();

    // ------------------------------------------------------------ fixtures

    private static TransactionView tx(long id, long accountId, String amount,
            String beneficiary, String country, String device, String ip) {
        return tx(id, accountId, amount, beneficiary, null, country, T, device, ip);
    }

    private static TransactionView tx(long id, long accountId, String amount, String beneficiary,
            Long beneficiaryAccountId, String country, OffsetDateTime date,
            String device, String ip) {
        return new TransactionView(id, accountId, null, new BigDecimal(amount), "TND",
                "TRANSFER", country, date, "ONLINE", ip, device, beneficiary,
                beneficiaryAccountId, "COMPLETED", T);
    }

    private static CustomerView customer(long id, String country) {
        return new CustomerView(id, "Amina", "Ben Salah", "amina@example.com",
                OffsetDateTime.parse("2000-01-01T00:00:00Z").toLocalDate(), country, "Tunis",
                "INDIVIDUAL", "ACTIVE", T);
    }

    private static AccountView account(long id) {
        return new AccountView(id, "TN-" + id, "CHECKING", "TND",
                new BigDecimal("50000"), T.toLocalDate(), "ACTIVE");
    }

    private static FullAnalysis fullAnalysis(TransactionView current, CustomerView customer,
            List<AccountView> accounts, List<TransactionView> scan) {
        return fullAnalysis(current, customer, accounts, scan, new BigDecimal("87"), 0);
    }

    private static FullAnalysis fullAnalysis(TransactionView current, CustomerView customer,
            List<AccountView> accounts, List<TransactionView> scan, BigDecimal score,
            long scanTotal) {
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, current.transactionId(),
                score, "HIGH", "HOLD", T, "v1", List.of());
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, current, customer,
                accounts, List.of(current), scan, scanTotal);
        return new FullAnalysis(ctx, List.<Insight>of(), null);
    }

    private static Optional<ConstellationNode> node(Graph g, String type) {
        return g.nodes().stream().filter(n -> type.equals(n.type())).findFirst();
    }

    private static Optional<ConstellationNode> byId(Graph g, String id) {
        return g.nodes().stream().filter(n -> id.equals(n.id())).findFirst();
    }

    private static boolean hasEdge(Graph g, String type) {
        return g.edges().stream().anyMatch(e -> type.equals(e.type()));
    }

    private static ConstellationEdge edge(Graph g, String type) {
        return g.edges().stream().filter(e -> type.equals(e.type())).findFirst().orElseThrow();
    }

    private static long sharedAccountCount(Graph g) {
        return g.nodes().stream()
                .filter(n -> "ACCOUNT".equals(n.type()))
                .filter(n -> !"acc:7".equals(n.id()))
                .count();
    }

    // --------------------------------------------------------------- tests

    @Test
    void buildCreatesAlertTransactionCustomerAndAccountNodes() {
        TransactionView focal = tx(100, 7, "12500", "SARL MedTech", "TN", "DEV-7734", "41.226.10.8");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal)));

        assertTrue(node(g, "ALERT").isPresent(), "nœud ALERTE attendu");
        assertTrue(node(g, "TRANSACTION").isPresent(), "nœud TRANSACTION attendu");
        assertTrue(node(g, "CUSTOMER").isPresent(), "nœud CUSTOMER attendu");
        assertTrue(node(g, "ACCOUNT").isPresent(), "nœud ACCOUNT attendu");
        assertTrue(node(g, "BENEFICIARY").isPresent(), "nœud BÉNÉFICIAIRE attendu");
        assertTrue(node(g, "DEVICE").isPresent(), "nœud DEVICE attendu");
        assertTrue(node(g, "IP").isPresent(), "nœud IP attendu");
        assertTrue(node(g, "COUNTRY").isPresent(), "nœud PAYS attendu");
    }

    @Test
    void buildLinksCustomerToAccountToTransaction() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal)));

        assertTrue(hasEdge(g, "OWNS"), "arête OWNS client->compte attendue");
        assertTrue(hasEdge(g, "SENT"), "arête SENT compte->transaction attendue");
        assertTrue(hasEdge(g, "TO"), "arête TO transaction->bénéficiaire attendue");
        assertTrue(hasEdge(g, "USED_DEVICE"), "arête USED_DEVICE attendue");
        assertTrue(hasEdge(g, "FROM_IP"), "arête FROM_IP attendue");
        assertTrue(hasEdge(g, "IN_COUNTRY"), "arête IN_COUNTRY attendue");
    }

    @Test
    void sharedBeneficiaryRaisesRiskAndCreatesSharedAccountNodes() {
        TransactionView focal = tx(100, 7, "12500", "SARL MedTech", "TN", "DEV-7734", "41.226.10.8");
        TransactionView other1 = tx(200, 55, "9000", "SARL MedTech", "TN", "dX", "2.2.2.2");
        TransactionView other2 = tx(201, 61, "8000", "sarl medtech", "TN", "dY", "3.3.3.3");

        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, other1, other2)));

        // Le bénéficiaire payé par 3 comptes -> HIGH (signal réseau avéré)
        ConstellationNode ben = node(g, "BENEFICIARY").orElseThrow();
        assertEquals("HIGH", ben.risk());
        assertTrue(ben.subtitle().contains("payé par 3 comptes"), ben.subtitle());

        assertTrue(byId(g, "acc:55").isPresent(), "compte 55 relié attendu");
        assertTrue(byId(g, "acc:61").isPresent(), "compte 61 relié attendu");
        assertTrue(hasEdge(g, "SHARED_WITH"), "arête SHARED_WITH attendue");
    }

    // ------------------------------------- A. focus d'investigation vs risque

    @Test
    void focalNodesCarryEngineRiskAndNotForcedHigh() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal), new BigDecimal("25"), 1));

        // 25/100 -> LOW côté risk-service : le fait d'être la focale ne
        // fabrique AUCUN niveau de risque.
        ConstellationNode txFocal = node(g, "TRANSACTION").orElseThrow();
        assertEquals("LOW", txFocal.risk());
        assertTrue(txFocal.investigationFocus(), "la transaction est le centre");

        ConstellationNode cust = node(g, "CUSTOMER").orElseThrow();
        assertEquals("LOW", cust.risk());
        assertTrue(cust.investigationFocus());

        ConstellationNode acc = node(g, "ACCOUNT").orElseThrow();
        assertEquals("LOW", acc.risk());
        assertTrue(acc.investigationFocus());
    }

    @Test
    void nonFocalOwnedAccountsAreNotInflated() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"),
                List.of(account(7L), account(8L)), List.of(focal), new BigDecimal("87"), 1));

        ConstellationNode other = byId(g, "acc:8").orElseThrow();
        assertEquals("LOW", other.risk(), "aucune donnée n'étaye le risque d'un autre compte");
        assertFalse(other.investigationFocus());
        assertTrue(byId(g, "acc:7").orElseThrow().investigationFocus());
    }

    @Test
    void mediumEngineScoreYieldsMediumFocalRisk() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal), new BigDecimal("45"), 1));
        assertEquals("MEDIUM", node(g, "TRANSACTION").orElseThrow().risk());
    }

    // ----------------------------------------- B. fiabilité des relations

    @Test
    void deviceAndIpSharingNeverExceedMedium() {
        TransactionView focal = tx(100, 7, "100", "BEN", "TN", "DEV-SHARED", "10.0.0.1");
        TransactionView other1 = tx(200, 55, "50", "OTHER1", "TN", "DEV-SHARED", "10.0.0.2");
        TransactionView other2 = tx(201, 61, "50", "OTHER2", "TN", "DEV-SHARED", "10.0.0.3");

        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, other1, other2)));

        // Un appareil partagé est un SIGNAL, pas une preuve de fraude.
        assertEquals("MEDIUM", node(g, "DEVICE").orElseThrow().risk());
        // L'IP, elle, n'est partagée avec personne ici.
        assertEquals("LOW", node(g, "IP").orElseThrow().risk());
    }

    @Test
    void genericValuesNeverCreateSharedLinks() {
        TransactionView focal = tx(100, 7, "100", "BEN", "TN", "unknown", "10.0.0.1");
        List<TransactionView> scan = new ArrayList<>();
        scan.add(focal);
        for (long a = 200; a < 208; a++) {
            scan.add(tx(a, a, "50", "OTHER" + a, "TN", "unknown", "10.0." + (a - 199) + ".1"));
        }
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                scan));

        assertEquals(0, sharedAccountCount(g),
                "« unknown » ne crée aucune relation artificielle");
        assertFalse(hasEdge(g, "SHARED_WITH"));
    }

    @Test
    void staleTransactionsDoNotCreateLinks() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", "TN", "d1", "1.1.1.1");
        TransactionView old = tx(200, 55, "9000", "SARL MedTech", null, "TN",
                T.minusDays(200), "d2", "2.2.2.2");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, old)));

        assertEquals(0, sharedAccountCount(g),
                "un partage de plus de " + ConstellationGraphService.SHARING_WINDOW_DAYS
                        + " jours n'est plus un lien récent");
    }

    @Test
    void beneficiaryNamesAreNormalizedBeforeComparison() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", "TN", "d1", "1.1.1.1");
        TransactionView other = tx(200, 55, "9000", "  sarl   medtech  ", "TN", "d2", "2.2.2.2");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, other)));

        assertTrue(byId(g, "acc:55").isPresent(), "libellés normalisés -> même bénéficiaire");
    }

    @Test
    void beneficiaryNodePrefersStableAccountId() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", 99L, "TN", T, "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal)));

        ConstellationNode ben = byId(g, "ben:acc:99").orElseThrow();
        assertEquals("BENEFICIARY", ben.type());
        assertEquals("SARL MedTech", ben.label());
        assertEquals(99L, ben.meta().get("beneficiaryAccountId"));
        assertFalse(g.nodes().stream().anyMatch(n -> n.id().startsWith("ben:SARL")),
                "l'identifiant stable remplace le nom comme identité");
    }

    @Test
    void beneficiariesMatchedByStableIdEvenWhenNamesDiffer() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", 99L, "TN", T, "d1", "1.1.1.1");
        TransactionView other = tx(200, 55, "9000", "MEDTECH SARL", 99L, "TN", T, "d2", "2.2.2.2");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, other)));

        assertTrue(byId(g, "acc:55").isPresent(),
                "même beneficiaryAccountId = même bénéficiaire, même avec un nom différent");
    }

    @Test
    void sameNameDifferentStableIdIsNotTheSameBeneficiary() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", 99L, "TN", T, "d1", "1.1.1.1");
        TransactionView other = tx(200, 55, "9000", "SARL MedTech", 100L, "TN", T, "d2", "2.2.2.2");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, other)));

        assertEquals(0, sharedAccountCount(g),
                "identifiants stables DIFFÉRENTS : le nom identique ne prouve rien");
    }

    @Test
    void sharedEdgesCarrySourceTransactionAndDate() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", "TN", "d1", "1.1.1.1");
        TransactionView other = tx(200, 55, "9000", "SARL MedTech", "TN", "d2", "2.2.2.2");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal, other)));

        String evidence = edge(g, "SHARED_WITH").evidence();
        assertTrue(evidence.contains("tx #200"), "la preuve nomme la transaction source : " + evidence);
        assertTrue(evidence.contains("2026-10-06"), "la preuve porte la date : " + evidence);
        var trace = edge(g, "SHARED_BENEFICIARY");
        assertTrue(trace.source().startsWith("ben:"));
        assertEquals("acc:55", trace.target());
        assertEquals(List.of(200L), trace.proofTransactions().stream()
                .map(proof -> proof.transactionId()).toList());
        assertEquals(other.transactionDate(), trace.proofTransactions().getFirst().date());
    }

    // ------------------------------------------- bornage et sa déclaration

    @Test
    void sharedAccountResolutionIsBounded() {
        TransactionView focal = tx(100, 7, "100", "BEN", "TN", "DEV-SHARED", "9.9.9.9");
        List<TransactionView> scan = new ArrayList<>();
        scan.add(focal);
        for (long a = 100; a < 110; a++) {
            scan.add(tx(200 + a, a, "50", "OTHER", "TN", "DEV-SHARED", "8.8.8.8"));
        }
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                scan));

        assertTrue(sharedAccountCount(g) <= ConstellationGraphService.MAX_SHARED_ACCOUNTS,
                "résolution bornée à " + ConstellationGraphService.MAX_SHARED_ACCOUNTS
                        + ", obtenue " + sharedAccountCount(g));
    }

    @Test
    void truncationIsDeclaredRatherThanHidden() {
        TransactionView focal = tx(100, 7, "100", "BEN", "TN", "DEV-SHARED", "9.9.9.9");
        List<TransactionView> scan = new ArrayList<>();
        scan.add(focal);
        for (long a = 100; a < 110; a++) {
            scan.add(tx(200 + a, a, "50", "OTHER", "TN", "DEV-SHARED", "8.8.8.8"));
        }
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                scan));

        assertTrue(g.truncated(), "le bornage doit être DÉCLARÉ");
        assertEquals(10, g.sharedAccountCandidates());
        assertEquals(ConstellationGraphService.MAX_SHARED_ACCOUNTS, g.sharedAccountsShown());
        assertEquals(4, g.hiddenSharedAccounts());
    }

    @Test
    void sharedAccountSelectionIsDeterministic() {
        TransactionView focal = tx(100, 7, "100", "SARL MedTech", "TN", "DEV-1", "1.1.1.1");
        List<TransactionView> scan = new ArrayList<>();
        scan.add(focal);
        for (long a = 50; a < 58; a++) {
            scan.add(tx(200 + a, a, "50", "SARL MedTech", "TN", "DEV-" + a, "1.1.1." + a));
        }
        List<AccountView> accounts = List.of(account(7L));

        List<String> first = ids(service.build(fullAnalysis(focal, customer(42L, "TN"),
                accounts, scan)).nodes());
        List<String> second = ids(service.build(fullAnalysis(focal, customer(42L, "TN"),
                accounts, scan)).nodes());

        assertEquals(first, second, "deux appels doivent rendre le MÊME graphe, dans le même ordre");
    }

    @Test
    void scanTruncationIsCarriedInContext() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        FullAnalysis fa = fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal), new BigDecimal("10"), 1000);
        assertFalse(fa.context().globalScanComplete(),
                "1 transaction chargée sur 1000 déclarées = scan incomplet");

        FullAnalysis complete = fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal), new BigDecimal("10"), 1);
        assertTrue(complete.context().globalScanComplete());
    }

    private static List<String> ids(List<ConstellationNode> nodes) {
        return nodes.stream().map(ConstellationNode::id).collect(Collectors.toList());
    }

    // --------------------------------------------------- autres garanties

    @Test
    void geoAnomalyMarksCountryHighWhenCustomerCountryDiffers() {
        TransactionView focal = tx(100, 7, "100", "X", "DE", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal)));

        ConstellationNode ctry = node(g, "COUNTRY").orElseThrow();
        assertEquals("HIGH", ctry.risk());
        assertTrue(Boolean.TRUE.equals(ctry.meta().get("geoAnomaly")));
    }

    @Test
    void everyEdgeHasEvidenceAndEndpoints() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"), List.of(account(7L)),
                List.of(focal)));

        for (ConstellationEdge e : g.edges()) {
            assertFalse(e.evidence().isBlank(), "chaque arête doit porter sa preuve: " + e.id());
            assertTrue(g.nodes().stream().anyMatch(n -> n.id().equals(e.source())),
                    "source inconnue: " + e.source());
            assertTrue(g.nodes().stream().anyMatch(n -> n.id().equals(e.target())),
                    "cible inconnue: " + e.target());
        }
    }

    @Test
    void noCustomerGivesGraphWithoutCustomerNode() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", "d1", "1.1.1.1");
        Graph g = service.build(fullAnalysis(focal, null, List.of(account(7L)), List.of(focal)));

        assertFalse(node(g, "CUSTOMER").isPresent());
        assertTrue(node(g, "TRANSACTION").isPresent());
    }

    @Test
    void noTransactionGivesGraphWithAlertOnly() {
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "HIGH_AMOUNT", "HIGH", "OPEN",
                null, T, null);
        EvaluationView evaluation = new EvaluationView(1L, 99L, BigDecimal.ZERO, "LOW",
                "APPROVED", T, "v1", List.of());
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, null, null,
                List.of(), List.of(), List.of(), 0);
        Graph g = service.build(new FullAnalysis(ctx, List.<Insight>of(), null));

        assertEquals(1, g.nodes().size(), "seul le nœud ALERTE survit");
    }

    // --------------------------------------------------- agrégation M5 (groupe)

    @Test
    void alertGroupProducesGroupNodeWithEscalatedSeverity() {
        TransactionView focal = tx(100, 7, "100", "PALIER5-1", "TN", null, null);
        AlertView alert = new AlertView(1L, 1L, "ALT-1", "OTHER", "MEDIUM", "OPEN",
                null, T, null, 42L);
        var group = new tn.finrisk.aiservice.client.dto.AlertGroupView(42L, "ACCOUNT", "7",
                42L, T, 24, "HIGH", "OPEN", 12, new BigDecimal("1200.00"));
        EvaluationView evaluation = new EvaluationView(1L, 100L, new BigDecimal("45"),
                "MEDIUM", "PENDING_REVIEW", T, "v1", List.of());
        AnalysisContext ctx = new AnalysisContext(alert, evaluation, focal,
                customer(42L, "TN"), List.of(account(7L)), List.of(focal), List.of(focal),
                1, group);
        Graph g = service.build(new FullAnalysis(ctx, List.<Insight>of(), null));

        var grp = byId(g, "group:42");
        assertTrue(grp.isPresent(), "nœud GROUP attendu quand alert.groupId est renseigné");
        assertEquals("HIGH", grp.get().risk(),
                "la sévérité agrégée du groupe est HIGH (12 alertes >= 10)");
        assertEquals(12, grp.get().meta().get("relatedCount"));
        assertTrue(hasEdge(g, "BELONGS_TO"), "arête alerte -> groupe attendue");
    }

    @Test
    void noAlertGroupProducesNoGroupNode() {
        TransactionView focal = tx(100, 7, "100", "X", "TN", null, null);
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"),
                List.of(account(7L)), List.of(focal)));
        assertFalse(node(g, "GROUP").isPresent(), "pas de nœud GROUP sans groupId");
    }

    @Test
    void merchantNameAppearsInTransactionNodeMeta() {
        TransactionView focal = new TransactionView(100L, 7L, null, "Maison Meuble Tunisie",
                new BigDecimal("10000"), "TND", "PAYMENT", "TN", T, "WEB", null, null,
                "Maison Meuble Tunisie", null, "COMPLETED", T);
        Graph g = service.build(fullAnalysis(focal, customer(42L, "TN"),
                List.of(account(7L)), List.of(focal)));

        var txNode = byId(g, "tx:100").orElseThrow();
        assertEquals("Maison Meuble Tunisie", txNode.meta().get("merchantName"),
                "le nom du marchand doit figurer dans meta de la transaction");
        assertTrue(txNode.subtitle().contains("Maison Meuble Tunisie"),
                "le subtitle doit mentionner le marchand");
    }
}
