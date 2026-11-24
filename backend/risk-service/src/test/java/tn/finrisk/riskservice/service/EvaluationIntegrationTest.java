package tn.finrisk.riskservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.riskservice.client.AccountClient;
import tn.finrisk.riskservice.client.AlertClient;
import tn.finrisk.riskservice.client.DownstreamUnavailableException;
import tn.finrisk.riskservice.client.MerchantClient;
import tn.finrisk.riskservice.client.RemoteResourceNotFoundException;
import tn.finrisk.riskservice.client.TransactionClient;
import tn.finrisk.riskservice.client.dto.AccountView;
import tn.finrisk.riskservice.client.dto.MerchantView;
import tn.finrisk.riskservice.client.dto.TransactionView;

/**
 * Integration du pipeline complet sur finrisk_test :
 *  - services distants (transaction/account/merchant) MOCKES : aucun reseau ;
 *  - persistance REELLE : risk_assessments, risk_factors, risk_profiles.
 *
 * Scenarios golden (scores calcules a la main, regles seedees) :
 *  - transaction normale        ->  0.00  LOW        APPROVED
 *  - montant seul (0.25+0.10)   -> 35.00  MEDIUM     MONITORING
 *  - 3 signaux (0.275 x 1,2)    -> 33.00  MEDIUM     MONITORING
 *  - fraude (0.975 x 1,2, cap)  ->100.00  CRITICAL   HOLD
 *  - structuration 10x100       -> 66.00  HIGH       PENDING_REVIEW (012+008+002)
 *
 * risk.timezone=UTC (heure figee a 12h : jamais dans la bande 0h-5h) pour
 * que les scores soient deterministes a toute heure de lancement.
 */
@SpringBootTest(properties = {
        "risk.timezone=UTC",
        "risk.stats-window-days=90",
        "risk.engine-version=v1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvaluationIntegrationTest {

    private static final long CUSTOMER = 900001;
    private static final long ACCOUNT = 900002;
    private static final long TX_NORMALE = 900003;
    private static final long TX_GRANDE = 900004;
    private static final long TX_SUSPECTE = 900005;
    private static final long TX_FRAUDE = 900006;
    private static final long TX_INCONNUE = 900007;
    private static final long TX_HS = 900008;
    private static final long TX_STRUCTURATION = 900009;
    private static final String IDS =
            "900003,900004,900005,900006,900007,900008,900009";

    /** Tout le scenario se joue sur une date figee : aucune dependance a l'horloge. */
    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TransactionClient transactionClient;
    @MockitoBean
    private AccountClient accountClient;
    @MockitoBean
    private MerchantClient merchantClient;
    /** La creation d'alerte part vers alert-case-service : hors reseau ici. */
    @MockitoBean
    private AlertClient alertClient;

    private final AccountView account =
            new AccountView(ACCOUNT, CUSTOMER, "ITEST900002", "TND", new BigDecimal("5000.00"));

    // --------------------------------------------------------------- OUTILS

    @BeforeEach
    void seed() {
        purge();
        jdbc.update("INSERT INTO customers (customer_id, first_name, last_name, email, "
                        + "customer_type) VALUES (?,?,?,?, 'RETAIL')",
                CUSTOMER, "Test", "Moteur", "test.moteur.it@finrisk.tn");
        jdbc.update("INSERT INTO accounts (account_id, customer_id, account_number, "
                        + "account_type, currency) VALUES (?,?,?, 'CHECKING','TND')",
                ACCOUNT, CUSTOMER, "ITEST900002");
        for (long id : new long[]{TX_NORMALE, TX_GRANDE, TX_SUSPECTE, TX_FRAUDE,
                TX_INCONNUE, TX_HS, TX_STRUCTURATION}) {
            jdbc.update("INSERT INTO transactions (transaction_id, account_id, amount, "
                            + "currency, transaction_type, country, status) VALUES "
                            + "(?, ?, 40.00, 'TND', 'PAYMENT', 'TN', 'COMPLETED')",
                    id, ACCOUNT);
        }

        when(accountClient.getById(ACCOUNT)).thenReturn(account);
        when(accountClient.listByCustomer(CUSTOMER)).thenReturn(List.of(account));
    }

    @AfterEach
    void cleanup() {
        purge();
    }

    /** Purge + retour du catalogue a l'etat seed (11 regles actives). */
    private void purge() {
        jdbc.update("DELETE FROM risk_assessments WHERE transaction_id IN (" + IDS + ")");
        jdbc.update("DELETE FROM risk_profiles WHERE customer_id = ?", CUSTOMER);
        jdbc.update("DELETE FROM transactions WHERE transaction_id IN (" + IDS + ")");
        jdbc.update("DELETE FROM accounts WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM customers WHERE customer_id = ?", CUSTOMER);
        jdbc.update("UPDATE risk_rules SET enabled = TRUE");
    }

    private static TransactionView view(long id, String amount, String country,
                                        OffsetDateTime date, Long merchantId) {
        return new TransactionView(id, ACCOUNT, merchantId, new BigDecimal(amount), "TND",
                country, date, "PAYMENT", "COMPLETED", "WEB");
    }

    private static TransactionView virement(long id, String amount, OffsetDateTime date) {
        return new TransactionView(id, ACCOUNT, null, new BigDecimal(amount), "TND",
                "TN", date, "TRANSFER", "COMPLETED", "WEB");
    }

    private void stubTransaction(long id, TransactionView self, List<TransactionView> history) {
        when(transactionClient.getById(id)).thenReturn(self);
        when(transactionClient.listByAccountSince(eq(ACCOUNT), any())).thenReturn(history);
    }

    private void stubMarchand(long merchantId, int ageEnJours) {
        when(merchantClient.getById(merchantId)).thenReturn(new MerchantView(merchantId,
                "Boutique Test", OffsetDateTime.now(ZoneOffset.UTC).minusDays(ageEnJours)));
    }

    private void evaluer(long transactionId) throws Exception {
        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + transactionId + "}"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------- SCENARIOS GOLDEN

    @Test
    void transactionNormale_scoreZeroApprouvee() throws Exception {
        TransactionView self = view(TX_NORMALE, "40.00", "TN", T, null);
        stubTransaction(TX_NORMALE, self, List.of(self));

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_NORMALE + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore").value(0.00))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.engineVersion").value("v1"))
                // 6 neutralisations stockees : 003, 005, 006, 007, 008, 012
                .andExpect(jsonPath("$.factors.length()").value(6));

        Boolean snapshot = jdbc.queryForObject(
                "SELECT feature_snapshot IS NOT NULL FROM risk_profiles WHERE customer_id = ?",
                Boolean.class, CUSTOMER);
        assertThat(snapshot).isTrue();
        String niveau = jdbc.queryForObject(
                "SELECT profile_risk_level FROM risk_profiles WHERE customer_id = ?",
                String.class, CUSTOMER);
        assertThat(niveau).isEqualTo("LOW");
    }

    @Test
    void montantSeul_trentePointsMonitoring() throws Exception {
        TransactionView self = view(TX_GRANDE, "8000.00", "TN", T, null);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        history.add(view(1, "8000.00", "TN", T.minusHours(1), null));
        history.add(view(2, "8000.00", "TN", T.minusHours(2), null));
        history.add(view(3, "8000.00", "TN", T.minusHours(3), null));
        stubTransaction(TX_GRANDE, self, history);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_GRANDE + "}"))
                .andExpect(status().isCreated())
                // 001 pleine (0.25) + 008 pleine (0.10) = 35.00, 2 declenchees -> pas de bonus
                .andExpect(jsonPath("$.riskScore").value(35.00))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.decision").value("MONITORING"))
                .andExpect(jsonPath("$.factors.length()").value(5));
    }

    @Test
    void troisSignauxFaibles_bonusCumul() throws Exception {
        TransactionView self = view(TX_SUSPECTE, "7000.00", "DE", T, 888L);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 0; i < 80; i++) {
            history.add(view(1000 + i, "8000.00", "TN", T.minusDays(2 + i), 777L));
        }
        stubTransaction(TX_SUSPECTE, self, history);
        stubMarchand(888L, 10);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_SUSPECTE + "}"))
                .andExpect(status().isCreated())
                // 001 demi (0.125) + 003 (0.10) + 005 (0.05) = 0.275 x 100 x 1,20 = 33.00
                .andExpect(jsonPath("$.riskScore").value(33.00))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.decision").value("MONITORING"))
                .andExpect(jsonPath("$.factors.length()").value(5));
    }

    @Test
    void fraudeClassique_basculeHold() throws Exception {
        TransactionView self = view(TX_FRAUDE, "9000.00", "DE", T, 888L);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 1; i <= 10; i++) {
            history.add(view(2000 + i, "50.00", "TN", T.minusMinutes(5L * i), null));
            history.add(view(3000 + i, "50.00", "TN", T.minusDays(3 + i), null));
        }
        stubTransaction(TX_FRAUDE, self, history);
        stubMarchand(888L, 5);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_FRAUDE + "}"))
                .andExpect(status().isCreated())
                // 0.975 x 100 x 1,20 = 117 -> plafonne a 100 -> HOLD
                .andExpect(jsonPath("$.riskScore").value(100.00))
                .andExpect(jsonPath("$.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.decision").value("HOLD"))
                .andExpect(jsonPath("$.factors.length()").value(9));

        // Relecture : par id d'assessment (BDD) puis par transaction
        Long assessmentId = jdbc.queryForObject(
                "SELECT assessment_id FROM risk_assessments WHERE transaction_id = ?",
                Long.class, TX_FRAUDE);
        mvc.perform(get("/api/evaluations/" + assessmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("HOLD"))
                .andExpect(jsonPath("$.factors[0].reason").isNotEmpty());
        mvc.perform(get("/api/evaluations/transaction/" + TX_FRAUDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(100.00));
    }

    // ------------------------------------------------------- STRUCTURATION
    // Le scenario qui justifie RULE-012 : 10 petits virements du meme client
    // en 24h. Chacun pris isolement est anodin (100 TND << 5000, pas de pays
    // nouveau, pas de marchand nouveau). SANS RULE-012, meme avec la
    // velocite (seuil > 5) et le cumul, le score n'atteint que 25/100 -> LOW
    // -> l'argent sort.

    @Test
    void structurationDixVirements_basculeReview() throws Exception {
        TransactionView self = virement(TX_STRUCTURATION, "100.00", T);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        // 9 virements identiques dans l'heure precedente : 10 au total
        for (int i = 1; i <= 9; i++) {
            history.add(virement(4000 + i, "100.00", T.minusHours(i)));
        }
        // antecedents lointains : le client fait DE L'ORDINAIRE 100 TND de temps
        // en temps, ce qui rend le motif encore plus net (pas un gros depot
        // inexplique qui battrait la baseline)
        for (int i = 1; i <= 15; i++) {
            history.add(virement(5000 + i, "100.00", T.minusDays(5 + i)));
        }
        stubTransaction(TX_STRUCTURATION, self, history);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_STRUCTURATION + "}"))
                .andExpect(status().isCreated())
                // 002 pleine (0.15) + 008 pleine (0.10) + 012 pleine (0.30)
                // = 0.55 x 100 x 1,20 (3 declenchees -> bonus) = 66.00 -> HIGH.
                // Facteurs persists : 005, 006 (neutralisees) + 002, 008, 012
                // (declenchees) = 5.
                .andExpect(jsonPath("$.riskScore").value(66.00))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.decision").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.factors.length()").value(5));

        // La raison de RULE-012 doit etre LISIBLE pour l'analyste : combien de
        // petites transactions, et pour quel cumul.
        Long assessmentId = jdbc.queryForObject(
                "SELECT assessment_id FROM risk_assessments WHERE transaction_id = ?",
                Long.class, TX_STRUCTURATION);
        mvc.perform(get("/api/evaluations/" + assessmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factors[?(@.factorType=='HISTORY')].reason")
                        .isNotEmpty());
    }

    @Test
    void structurationTroisVirements_resteLow() throws Exception {
        // Le garde-fou inverse : en dessous du seuil (3), le motif n'existe pas
        // et la regle ne doit PAS artificiellement monter le score.
        TransactionView self = virement(TX_STRUCTURATION, "100.00", T);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 1; i <= 2; i++) {
            history.add(virement(4000 + i, "100.00", T.minusHours(i)));
        }
        for (int i = 1; i <= 15; i++) {
            history.add(virement(5000 + i, "100.00", T.minusDays(5 + i)));
        }
        stubTransaction(TX_STRUCTURATION, self, history);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_STRUCTURATION + "}"))
                .andExpect(status().isCreated())
                // 008 pleine (0.10) seule -> 10.00 (RULE-012 non declenchee :
                // 3 petites transactions n'atteignent pas le seuil > 3)
                .andExpect(jsonPath("$.riskScore").value(10.00))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.decision").value("APPROVED"));
    }

    // ------------------------------------------------------- REGLES A CHAUD

    @Test
    void regleDesactivee_leScoreChangeSansRedemarrage() throws Exception {
        TransactionView self = view(TX_GRANDE, "8000.00", "TN", T, null);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        history.add(view(1, "8000.00", "TN", T.minusHours(1), null));
        history.add(view(2, "8000.00", "TN", T.minusHours(2), null));
        history.add(view(3, "8000.00", "TN", T.minusHours(3), null));
        stubTransaction(TX_GRANDE, self, history);

        Long ruleId = jdbc.queryForObject(
                "SELECT rule_id FROM risk_rules WHERE rule_code = 'RULE-001'", Long.class);

        mvc.perform(put("/api/rules/" + ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Sans RULE-001 : plus que 008 (0.10) -> 10.00 APPROVED
        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_GRANDE + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore").value(10.00))
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        // Restaure explicitement (le purge() du @AfterEach double securite)
        mvc.perform(put("/api/rules/" + ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------- CATALOGUE/ERREURS

    @Test
    void catalogueDouzeReglesEtInvariantPoids() throws Exception {
        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].ruleCode").value("RULE-001"))
                .andExpect(jsonPath("$[11].ruleCode").value("RULE-012"));

        // Somme = 1.35 (reequilibrage v2 : RULE-002 seuil 10->5,
        // RULE-008 poids 0.05->0.10) ; RULE-012 reste le poids max a 0.30.
        BigDecimal somme = jdbc.queryForObject(
                "SELECT COALESCE(SUM(weight), 0) FROM risk_rules WHERE enabled",
                BigDecimal.class);
        assertThat(somme).isEqualByComparingTo("1.35");
        Long actives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_rules WHERE enabled", Long.class);
        assertThat(actives).isEqualTo(12L);
    }

    @Test
    void rule012_appliqueeSurLeCatalogue() throws Exception {
        BigDecimal poids = jdbc.queryForObject(
                "SELECT weight FROM risk_rules WHERE rule_code = 'RULE-012'", BigDecimal.class);
        assertThat(poids).isEqualByComparingTo("0.30");
        String categorie = jdbc.queryForObject(
                "SELECT risk_category FROM risk_rules WHERE rule_code = 'RULE-012'",
                String.class);
        assertThat(categorie).isEqualTo("HISTORY");
    }

    @Test
    void transactionInconnue_404_etAmontHorsService_503() throws Exception {
        when(transactionClient.getById(TX_INCONNUE))
                .thenThrow(new RemoteResourceNotFoundException("transaction", TX_INCONNUE));
        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_INCONNUE + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("transaction introuvable chez le service distant : id = 900007"));

        when(transactionClient.getById(TX_HS))
                .thenThrow(new DownstreamUnavailableException("transaction-service", null));
        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":" + TX_HS + "}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Service distant indisponible : transaction-service"));
    }

    @Test
    void evaluationJamaisFaite_404() throws Exception {
        mvc.perform(get("/api/evaluations/transaction/" + TX_NORMALE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Evaluation introuvable : transactionId = 900003"));
    }

    // ------------------------------------------------ APPLICATION DU VERDICT
    // Validation manuelle obligatoire : le moteur NE CHANGE JAMAIS le statut
    // d'une transaction. Une operation est liquidee par un ANALYSTE, pas par
    // un score — sinon le moindre virement banal echapperait a tout examen.

    @Test
    void approuvee_neChangePasDeStatut() throws Exception {
        TransactionView self = view(TX_NORMALE, "40.00", "TN", T, null);
        stubTransaction(TX_NORMALE, self, List.of(self));

        evaluer(TX_NORMALE);

        verify(transactionClient, never()).changeStatus(any(), any());
    }

    @Test
    void hold_neChangePasDeStatut() throws Exception {
        TransactionView self = view(TX_FRAUDE, "900000.00", "DE", T, 888L);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 1; i <= 10; i++) {
            history.add(view(2000 + i, "50.00", "TN", T.minusMinutes(5L * i), null));
            history.add(view(3000 + i, "50.00", "TN", T.minusDays(3 + i), null));
        }
        stubTransaction(TX_FRAUDE, self, history);
        stubMarchand(888L, 5);

        evaluer(TX_FRAUDE);

        // Meme un score de 87 ne bloque pas la transaction ici : c'est l'alerte
        // qui porte la gravite, l'analyste decide de la suite.
        verify(transactionClient, never()).changeStatus(any(), any());
    }

    @Test
    void monitoring_neChangePasDeStatut() throws Exception {
        TransactionView self = view(TX_GRANDE, "8000.00", "TN", T, null);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        history.add(view(1, "8000.00", "TN", T.minusHours(1), null));
        history.add(view(2, "8000.00", "TN", T.minusHours(2), null));
        history.add(view(3, "8000.00", "TN", T.minusHours(3), null));
        stubTransaction(TX_GRANDE, self, history);

        evaluer(TX_GRANDE);

        verify(transactionClient, never()).changeStatus(any(), any());
    }

    // ------------------------------------------------------------- TRACABILITE
    // Une transaction qui n'ouvre AUCUNE alerte n'apparait nulle part cote
    // analyste : elle serait liquidee sans laisser de trace exploitable.

    @Test
    void transactionAnodine_openQuandMemeUneAlerte() throws Exception {
        TransactionView self = view(TX_NORMALE, "40.00", "TN", T, null);
        stubTransaction(TX_NORMALE, self, List.of(self));

        evaluer(TX_NORMALE);

        verify(alertClient).createAlert(any(), eq("OTHER"));
    }

    @Test
    void transactionSuspecte_openAussiUneAlerte() throws Exception {
        TransactionView self = view(TX_FRAUDE, "900000.00", "DE", T, 888L);
        List<TransactionView> history = new ArrayList<>();
        history.add(self);
        for (int i = 1; i <= 10; i++) {
            history.add(view(2000 + i, "50.00", "TN", T.minusMinutes(5L * i), null));
            history.add(view(3000 + i, "50.00", "TN", T.minusDays(3 + i), null));
        }
        stubTransaction(TX_FRAUDE, self, history);
        stubMarchand(888L, 5);

        evaluer(TX_FRAUDE);

        verify(alertClient).createAlert(any(), eq("OTHER"));
    }
}
