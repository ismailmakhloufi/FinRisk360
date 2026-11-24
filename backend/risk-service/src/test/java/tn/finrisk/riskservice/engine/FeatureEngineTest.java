package tn.finrisk.riskservice.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tn.finrisk.riskservice.client.dto.TransactionView;

/**
 * Tests unitaires du FEATURE ENGINE (pur : ni base, ni reseau).
 * Chaque test verifie UN calcul : baseline, fenetres glissantes, percentiles,
 * heure locale, couples suspects.
 */
class FeatureEngineTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");
    private static final BigDecimal SMALL_MAX = new BigDecimal("5000");

    private final FeatureEngine engine = new FeatureEngine("UTC", 90, SMALL_MAX);

    private static TransactionView tx(long id, String amount, String country,
                                      OffsetDateTime date, Long merchantId, String status) {
        return new TransactionView(id, 1L, merchantId, new BigDecimal(amount), "TND",
                country, date, "PAYMENT", status, "WEB");
    }

    private static TransactionView typed(long id, String amount, OffsetDateTime date, String type) {
        return new TransactionView(id, 1L, null, new BigDecimal(amount), "TND",
                "TN", date, type, "COMPLETED", "WEB");
    }

    @Test
    void sansTransactionPrealable_pasDeBaseline() {
        TransactionView self = tx(1, "40.00", "TN", T, null, "COMPLETED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self), null);

        assertThat(f.hasBaseline()).isFalse();
        assertThat(f.stats()).isNull();
    }

    @Test
    void baseline_exclutLaTransactionEvaluee() {
        TransactionView self = tx(3, "9000.00", "TN", T, null, "COMPLETED");
        TransactionView h1 = tx(1, "40.00", "TN", T.minusDays(1), null, "COMPLETED");
        TransactionView h2 = tx(2, "42.00", "TN", T.minusDays(2), null, "COMPLETED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, h1, h2), null);

        assertThat(f.hasBaseline()).isTrue();
        assertThat(f.stats().size()).isEqualTo(2);
        assertThat(f.stats().avg()).isEqualByComparingTo("41.00");
        assertThat(f.stats().p95()).isEqualByComparingTo("42.00");
    }

    @Test
    void heureLocale_convertieEnTunis() {
        FeatureEngine tunis = new FeatureEngine("Africa/Tunis", 90, SMALL_MAX);
        TransactionView self = tx(1, "40.00", "TN",
                OffsetDateTime.parse("2026-01-15T00:30:00Z"), null, "COMPLETED");

        TransactionFeatures f = tunis.compute(self, 10L, List.of(self), null);

        assertThat(f.localHour()).isEqualTo(1); // UTC+1 (pas d'heure d'ete)
    }

    @Test
    void fenetre24h_glissanteAncreeSurLaTransaction() {
        TransactionView self = tx(3, "40.00", "TN", T, null, "COMPLETED");
        TransactionView tropTot = tx(1, "40.00", "TN", T.minusHours(25), null, "COMPLETED");
        TransactionView dedans = tx(2, "40.00", "TN", T.minusHours(23), null, "COMPLETED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, tropTot, dedans), null);

        // 25h avant = hors fenetre meme si meme jour calendaire
        assertThat(f.activity().txCount24h()).isEqualTo(2);
    }

    @Test
    void activite24h_cumulEtEchecs() {
        TransactionView self = tx(3, "100.00", "TN", T, null, "COMPLETED");
        TransactionView ok = tx(1, "50.00", "TN", T.minusHours(2), null, "COMPLETED");
        TransactionView ko = tx(2, "60.00", "TN", T.minusHours(3), null, "FAILED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, ok, ko), null);

        assertThat(f.activity().txCount24h()).isEqualTo(3);
        assertThat(f.activity().cumul24hTnd()).isEqualByComparingTo("210.00");
        assertThat(f.activity().failedCount24h()).isEqualTo(1);
    }

    @Test
    void paysDifferents_minGapEntreAdjacentes() {
        TransactionView self = tx(2, "9000.00", "DE", T, null, "COMPLETED");
        TransactionView avant = tx(1, "40.00", "TN", T.minusMinutes(30), null, "COMPLETED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, avant), null);

        assertThat(f.activity().minGapCountryMinutes()).isEqualTo(30);
    }

    @Test
    void doublon_groupeParMarchandEtMontant() {
        TransactionView premier = tx(1, "50.00", "TN", T.minusMinutes(60), 777L, "COMPLETED");
        TransactionView autre = tx(2, "10.00", "TN", T.minusMinutes(30), 999L, "COMPLETED");
        TransactionView self = tx(3, "50.00", "TN", T.minusMinutes(10), 777L, "COMPLETED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, autre, premier), null);

        // Malgre la transaction intercalaire : 60m -> 10m = 50 min
        assertThat(f.activity().minGapDuplicateMinutes()).isEqualTo(50);
    }

    @Test
    void nouveauMarchand_uniquementSiJamaisVu() {
        TransactionView self = tx(2, "40.00", "TN", T, 888L, "COMPLETED");
        TransactionView autre = tx(1, "40.00", "TN", T.minusDays(1), 777L, "COMPLETED");
        TransactionFeatures inconnu = engine.compute(self, 10L, List.of(self, autre), 10);
        assertThat(inconnu.activity().newMerchantForCustomer()).isTrue();

        TransactionView revu = tx(1, "40.00", "TN", T.minusDays(1), 888L, "COMPLETED");
        TransactionFeatures connu = engine.compute(self, 10L, List.of(self, revu), 10);
        assertThat(connu.activity().newMerchantForCustomer()).isFalse();

        TransactionView sansMarchand = tx(9, "40.00", "TN", T, null, "COMPLETED");
        TransactionFeatures vide = engine.compute(sansMarchand, 10L, List.of(sansMarchand), null);
        assertThat(vide.activity().newMerchantForCustomer()).isFalse();
    }

    @Test
    void percentiles_nearestRankSurLesAntecedents() {
        TransactionView self = tx(99, "200.00", "TN", T, null, "COMPLETED");
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 1; i <= 19; i++) {
            history.add(tx(i, BigDecimal.valueOf(i * 10).toPlainString(),
                    "TN", T.minusDays(i), null, "COMPLETED"));
        }

        TransactionFeatures f = engine.compute(self, 10L, history, null);

        assertThat(f.stats().size()).isEqualTo(19); // sans la transaction evaluee
        assertThat(f.stats().avg()).isEqualByComparingTo("100.00");
        assertThat(f.stats().median()).isEqualByComparingTo("100"); // rang ceil(9.5)=10
        assertThat(f.stats().p95()).isEqualByComparingTo("190");    // rang ceil(18.05)=19
        assertThat(f.stats().p99()).isEqualByComparingTo("190");    // rang ceil(18.81)=19
    }

    @Test
    void modePays_majoriteTN() {
        TransactionView self = tx(4, "40.00", "DE", T, null, "COMPLETED");
        TransactionView t1 = tx(1, "40.00", "TN", T.minusDays(1), null, "COMPLETED");
        TransactionView t2 = tx(2, "40.00", "TN", T.minusDays(2), null, "COMPLETED");
        TransactionView t3 = tx(3, "40.00", "TN", T.minusDays(3), null, "COMPLETED");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, t1, t2, t3), null);

        assertThat(f.stats().usualCountry()).isEqualTo("TN");
    }

    @Test
    void deviseNonTnd_signale() {
        TransactionView self = new TransactionView(1L, 1L, null, new BigDecimal("40"),
                "EUR", "TN", T, "PAYMENT", "COMPLETED", "WEB");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self), null);

        assertThat(f.tnd()).isFalse();
    }

    // ------------------------------------------------- STRUCTURATION (RULE-012)

    @Test
    void structuration_dixPetitsVirements() {
        // 10 virements de 100 TND en 24h : le SCENARIO de reference. Aucun montant
        // ne depasse 5000, donc il faut bien que le MOTIF soit compte quelque part.
        TransactionView self = typed(10, "100.00", T, "TRANSFER");
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 1; i <= 9; i++) {
            history.add(typed(i, "100.00", T.minusHours(i), "TRANSFER"));
        }

        TransactionFeatures f = engine.compute(self, 10L, history, null);

        assertThat(f.activity().smallTxCount24h()).isEqualTo(10);
        assertThat(f.activity().smallTxSum24h()).isEqualByComparingTo("1000.00");
    }

    @Test
    void structuration_exclutLesDepots() {
        // Un DEPOSIT n'est pas de la structuration : l'argent ENTRE, rien a fractionner.
        TransactionView self = typed(3, "100.00", T, "TRANSFER");
        TransactionView depot1 = typed(1, "100.00", T.minusHours(1), "DEPOSIT");
        TransactionView depot2 = typed(2, "100.00", T.minusHours(2), "DEPOSIT");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, depot1, depot2), null);

        assertThat(f.activity().smallTxCount24h()).isEqualTo(1); // la seule transfert
        assertThat(f.activity().smallTxSum24h()).isEqualByComparingTo("100.00");
    }

    @Test
    void structuration_exclutLesGrosMontants() {
        // 3 virements au-dessus du plafond "petit montant" : ce n'est pas du
        // fractionnement, c'est RULE-001 (montant) qui doit les traiter.
        TransactionView self = typed(3, "8000.00", T, "TRANSFER");
        TransactionView a = typed(1, "8000.00", T.minusHours(1), "TRANSFER");
        TransactionView b = typed(2, "8000.00", T.minusHours(2), "TRANSFER");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, a, b), null);

        assertThat(f.activity().smallTxCount24h()).isZero();
        assertThat(f.activity().smallTxSum24h()).isEqualByComparingTo("0");
    }

    @Test
    void structuration_fenetreGlissanteHors24hIgnoree() {
        TransactionView self = typed(2, "100.00", T, "TRANSFER");
        TransactionView tropTot = typed(1, "100.00", T.minusHours(25), "TRANSFER");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, tropTot), null);

        assertThat(f.activity().smallTxCount24h()).isEqualTo(1); // hors fenetre
    }

    @Test
    void structuration_deviseEtrangereIgnoree() {
        TransactionView self = new TransactionView(1L, 1L, null, new BigDecimal("100"),
                "EUR", "TN", T, "TRANSFER", "COMPLETED", "WEB");
        TransactionView autre = new TransactionView(2L, 1L, null, new BigDecimal("100"),
                "EUR", "TN", T.minusHours(1), "TRANSFER", "COMPLETED", "WEB");

        TransactionFeatures f = engine.compute(self, 10L, List.of(self, autre), null);

        assertThat(f.activity().smallTxCount24h()).isZero();
        assertThat(f.activity().smallTxSum24h()).isEqualByComparingTo("0");
    }
}
