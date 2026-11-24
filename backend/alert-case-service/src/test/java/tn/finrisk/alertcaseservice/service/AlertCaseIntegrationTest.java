package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.alertcaseservice.client.AccountClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AccountView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Integration du jalon M4 sur finrisk_test :
 *  - risk-service MOCKE (@MockitoBean RiskClient) : aucun reseau ;
 *  - persistance REELLE : alerts, cases, case_comments, audit_logs ;
 *  - scenario GOLDEN « alerte -> case -> commentaire -> cloture tracee » :
 *    creation depuis une evaluation, transitions legales/illlegales,
 *    doublon, cloture, et surtout l'AUDIT : chaque mutation laisse sa trace,
 *    la cloture comprise.
 *
 * Seed : user 900001 (X-User-Id), customer/account/tx/assessment 900011..900014
 * (+ assessment 900015 pour le 2e scénario), purge complete a chaque test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertCaseIntegrationTest {

    private static final long USER = 900001;
    private static final long CUSTOMER = 900011;
    private static final long ACCOUNT = 900012;
    private static final long TX = 900013;
    private static final long ASSESSMENT = 900014;
    private static final long ASSESSMENT2 = 900015;
    /** Compte/tx/evaluation ISoles : sert aux scenarios qui exigent une
     *  alerte non absorbee (hors agrégation sur le meme compte). */
    private static final long ACCOUNT3 = 900018;
    private static final long TX3 = 900019;
    private static final long ASSESSMENT3 = 900020;
    private static final long INCONNU = 999999;
    private static final long HS = 999998;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private RiskClient riskClient;
    /** Agregation M5 : clients distants MOCKES (aucun reseau), SQL REELLE. */
    @MockitoBean
    private TransactionClient transactionClient;
    @MockitoBean
    private AccountClient accountClient;

    // --------------------------------------------------------------- OUTILS

    @BeforeEach
    void seed() {
        purge();
        jdbc.update("INSERT INTO users (user_id, username, email, password_hash, role) "
                        + "VALUES (?, 'itest.analyst', 'itest.analyst@finrisk.tn', 'hash', 'ANALYST')",
                USER);
        jdbc.update("INSERT INTO customers (customer_id, first_name, last_name, email, "
                        + "customer_type) VALUES (?,?,?,?, 'RETAIL')",
                CUSTOMER, "Test", "Alerte", "test.alerte.it@finrisk.tn");
        jdbc.update("INSERT INTO accounts (account_id, customer_id, account_number, "
                        + "account_type, currency) VALUES (?,?,?, 'CHECKING','TND')",
                ACCOUNT, CUSTOMER, "ITEST900012");
        jdbc.update("INSERT INTO transactions (transaction_id, account_id, amount, currency, "
                        + "transaction_type, country, status) "
                        + "VALUES (?, ?, 8500.00, 'TND', 'PAYMENT', 'DE', 'COMPLETED')",
                TX, ACCOUNT);
        jdbc.update("INSERT INTO accounts (account_id, customer_id, account_number, "
                        + "account_type, currency) VALUES (?,?,?, 'CHECKING','TND')",
                ACCOUNT3, CUSTOMER, "ITEST900018");
        jdbc.update("INSERT INTO transactions (transaction_id, account_id, amount, currency, "
                        + "transaction_type, country, status) "
                        + "VALUES (?, ?, 300.00, 'TND', 'PAYMENT', 'TN', 'COMPLETED')",
                TX3, ACCOUNT3);
        for (long id : new long[]{ASSESSMENT, ASSESSMENT2}) {
            jdbc.update("INSERT INTO risk_assessments (assessment_id, transaction_id, "
                            + "risk_score, risk_level, decision, assessed_at, engine_version) "
                            + "VALUES (?, ?, 72.00, 'HIGH', 'PENDING_REVIEW', "
                            + "'2026-01-15T12:00:00Z', 'v1')",
                    id, TX);
        }
        jdbc.update("INSERT INTO risk_assessments (assessment_id, transaction_id, "
                        + "risk_score, risk_level, decision, assessed_at, engine_version) "
                        + "VALUES (?, ?, 55.00, 'MEDIUM', 'PENDING_REVIEW', "
                        + "'2026-01-15T12:00:00Z', 'v1')",
                ASSESSMENT3, TX3);
        // risk-service MOCKE : trois evaluations connues, deux pannes simulees
        when(riskClient.getEvaluationById(ASSESSMENT)).thenReturn(
                new EvaluationView(ASSESSMENT, TX, new BigDecimal("72.00"), "HIGH",
                        "PENDING_REVIEW", OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1"));
        when(riskClient.getEvaluationById(ASSESSMENT2)).thenReturn(
                new EvaluationView(ASSESSMENT2, TX, new BigDecimal("72.00"), "HIGH",
                        "PENDING_REVIEW", OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1"));
        when(riskClient.getEvaluationById(ASSESSMENT3)).thenReturn(
                new EvaluationView(ASSESSMENT3, TX3, new BigDecimal("55.00"), "MEDIUM",
                        "PENDING_REVIEW", OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1"));
        when(riskClient.getEvaluationById(INCONNU))
                .thenThrow(new RemoteResourceNotFoundException("evaluation", INCONNU));
        when(riskClient.getEvaluationById(HS))
                .thenThrow(new DownstreamUnavailableException("risk-service", null));
        // Agregation M5 : la transaction et le compte sont lus CHEZ leurs
        // services (M0) — mockes ici, le SQL des natives queries est REEL.
        when(transactionClient.getById(TX)).thenReturn(
                new TransactionView(TX, ACCOUNT, new BigDecimal("8500.00"), "TND",
                        "PAYMENT", OffsetDateTime.parse("2026-01-15T12:00:00Z"),
                        "Dupont", null, "COMPLETED"));
        when(transactionClient.getById(TX3)).thenReturn(
                new TransactionView(TX3, ACCOUNT3, new BigDecimal("300.00"), "TND",
                        "PAYMENT", OffsetDateTime.parse("2026-01-15T12:00:00Z"),
                        "Fournisseur", null, "COMPLETED"));
        when(accountClient.getById(ACCOUNT)).thenReturn(
                new AccountView(ACCOUNT, CUSTOMER, "ITEST900012", "TND"));
        when(accountClient.getById(ACCOUNT3)).thenReturn(
                new AccountView(ACCOUNT3, CUSTOMER, "ITEST900018", "TND"));
    }

    @AfterEach
    void cleanup() {
        purge();
    }

    /** Purge dans l'ordre des FK : commentaires -> cases -> audit -> alertes
     *  -> groupes (apres alertes : fk_alerts_group = SET NULL) -> assessments
     *  (la cascade supprimerait les alertes) -> socle. */
    private void purge() {
        jdbc.update("DELETE FROM case_comments");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM alerts");
        // Les groupes doivent partir APRES les alertes, sinon l'index unique
        // partiel (un seul groupe ouvert par perimetre) resterait occupe et
        // la creation suivante echouerait en silence.
        jdbc.update("DELETE FROM alert_groups");
        jdbc.update("DELETE FROM risk_assessments WHERE assessment_id IN (?, ?, ?)",
                ASSESSMENT, ASSESSMENT2, ASSESSMENT3);
        jdbc.update("DELETE FROM transactions WHERE transaction_id IN (?, ?)", TX, TX3);
        jdbc.update("DELETE FROM accounts WHERE account_id IN (?, ?)", ACCOUNT, ACCOUNT3);
        jdbc.update("DELETE FROM customers WHERE customer_id = ?", CUSTOMER);
        jdbc.update("DELETE FROM users WHERE user_id = ?", USER);
    }

    private long creerAlerte(long assessmentId) throws Exception {
        mvc.perform(post("/api/alerts")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + assessmentId
                                + ",\"alertType\":\"HIGH_AMOUNT\"}"))
                .andExpect(status().isCreated());
        return alerteId(assessmentId);
    }

    private long alerteId(long assessmentId) {
        return jdbc.queryForObject("SELECT alert_id FROM alerts WHERE assessment_id = ?",
                Long.class, assessmentId);
    }

    private long caseId(long alertId) {
        return jdbc.queryForObject("SELECT case_id FROM cases WHERE alert_id = ?",
                Long.class, alertId);
    }

    private void changerStatutAlerte(long alertId, String statut) throws Exception {
        mvc.perform(patch("/api/alerts/" + alertId + "/status")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + statut + "\"}"))
                .andExpect(status().isOk());
    }

    private void ouvrirCase(long alertId) throws Exception {
        mvc.perform(post("/api/alerts/" + alertId + "/cases")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------- SCENARIO GOLDEN

    @Test
    void scenarioComplet_alerteCaseCommentaireClotureTracee() throws Exception {
        // 1. une evaluation devient une alerte : severite derivee du verdict HIGH
        mvc.perform(post("/api/alerts")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + ASSESSMENT
                                + ",\"alertType\":\"HIGH_AMOUNT\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/alerts/" + alerteId(ASSESSMENT)))
                .andExpect(jsonPath("$.alertCode").value("ALT-900014"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()));
        long alertId = alerteId(ASSESSMENT);

        // 2. UNE SEULE alerte par evaluation -> 409
        mvc.perform(post("/api/alerts")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + ASSESSMENT
                                + ",\"alertType\":\"OTHER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Une alerte existe deja")));

        // 3. transitions d'alerte : graphe respecte, hors graphe -> 409
        mvc.perform(patch("/api/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Transition d'alerte illegale : OPEN -> IN_PROGRESS"));
        changerStatutAlerte(alertId, "ACKNOWLEDGED");
        changerStatutAlerte(alertId, "IN_PROGRESS");

        // 4. ouverture du dossier d'enquete (une seule fois)
        ouvrirCase(alertId);
        mvc.perform(post("/api/alerts/" + alertId + "/cases")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Une case existe deja")));
        long caseId = caseId(alertId);
        mvc.perform(get("/api/alerts/" + alertId + "/case"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.assignedTo").value(USER));

        // 5. commentaire SANS auteur -> 400 (user_id NOT NULL, pas d'anonymat)
        mvc.perform(post("/api/cases/" + caseId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"anonyme\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("X-User-Id")));

        // 6. fil d'enquete : EVIDENCE puis revue puis NOTE puis decision
        mvc.perform(post("/api/cases/" + caseId + "/comments")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Virement 8500 DE 5 min avant\","
                                + "\"commentType\":\"EVIDENCE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentType").value("EVIDENCE"))
                .andExpect(jsonPath("$.userId").value(USER));
        mvc.perform(patch("/api/cases/" + caseId + "/status")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/" + caseId + "/comments")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Client contacte, negation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentType").value("NOTE"));
        mvc.perform(patch("/api/cases/" + caseId + "/status")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\","
                                + "\"investigationResult\":\"CONFIRMED_FRAUD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationResult").value("CONFIRMED_FRAUD"));

        // 7. cloture : closedAt renseigne, fin de toutes les mutations
        mvc.perform(patch("/api/cases/" + caseId + "/status")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(org.hamcrest.Matchers.notNullValue()));
        mvc.perform(post("/api/cases/" + caseId + "/comments")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"trop tard\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Case cloturee")));
        mvc.perform(patch("/api/cases/" + caseId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict());

        // 8. l'alerte se resout apres la cloture : resolvedAt renseigne
        changerStatutAlerte(alertId, "RESOLVED");
        mvc.perform(patch("/api/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Transition d'alerte illegale")));

        // 9. le fil : chronologique, les deux commentaires conserves
        mvc.perform(get("/api/cases/" + caseId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].commentType").value("EVIDENCE"))
                .andExpect(jsonPath("$[1].commentType").value("NOTE"));

        // 10. LA TRACE (jalon M4) : case = 6 entrees chronologiques
        //     [0] ouverture, [1] preuve EVIDENCE, [2] mise en revue,
        //     [3] note, [4] confirmation, [5] cloture
        mvc.perform(get("/api/audit")
                        .param("entityType", "CASE").param("entityId", Long.toString(caseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].action").value("CASE_OPENED"))
                .andExpect(jsonPath("$[0].oldValue").value(nullValue()))
                .andExpect(jsonPath("$[0].newValue").value("OPEN"))
                .andExpect(jsonPath("$[1].action").value("COMMENT_ADDED"))
                .andExpect(jsonPath("$[1].newValue").value("EVIDENCE"))
                .andExpect(jsonPath("$[2].action").value("CASE_STATUS_CHANGED"))
                .andExpect(jsonPath("$[2].oldValue").value("OPEN"))
                .andExpect(jsonPath("$[2].newValue").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[3].action").value("COMMENT_ADDED"))
                .andExpect(jsonPath("$[3].newValue").value("NOTE"))
                .andExpect(jsonPath("$[4].action").value("CASE_STATUS_CHANGED"))
                .andExpect(jsonPath("$[4].oldValue").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[4].newValue").value("CONFIRMED"))
                .andExpect(jsonPath("$[5].action").value("CASE_STATUS_CHANGED"))
                .andExpect(jsonPath("$[5].newValue").value("CLOSED"));

        // ... et alerte = 4 entrees (creation + 3 transitions)
        mvc.perform(get("/api/audit")
                        .param("entityType", "ALERT").param("entityId", Long.toString(alertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].action").value("ALERT_CREATED"))
                .andExpect(jsonPath("$[1].newValue").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$[2].newValue").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[3].newValue").value("RESOLVED"))
                .andExpect(jsonPath("$[3].oldValue").value("IN_PROGRESS"));

        // 11. relectures paginees
        mvc.perform(get("/api/alerts").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].alertCode").value("ALT-900014"));
        mvc.perform(get("/api/cases").param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        // 11 = 4 alerte + 6 case + 1 ALERT_GROUP_CREATED (l'agregation M5
        // trace l'ouverture du groupe a chaque creation d'alerte rattachee).
        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(11));

        // 12. etat final en base : clotures toutes deux, trace complete
        String etatCase = jdbc.queryForObject(
                "SELECT status FROM cases WHERE case_id = ?", String.class, caseId);
        assertThat(etatCase).isEqualTo("CLOSED");
        OffsetDateTime closedAt = jdbc.queryForObject(
                "SELECT closed_at FROM cases WHERE case_id = ?", OffsetDateTime.class, caseId);
        assertThat(closedAt).isNotNull();
        OffsetDateTime resolvedAt = jdbc.queryForObject(
                "SELECT resolved_at FROM alerts WHERE alert_id = ?", OffsetDateTime.class, alertId);
        assertThat(resolvedAt).isNotNull();
        Long traces = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        assertThat(traces).isEqualTo(11L); // 4 alerte + 6 case + 1 groupe
        Long commentaires = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_comments WHERE case_id = ?", Long.class, caseId);
        assertThat(commentaires).isEqualTo(2L);
    }

    // -------------------------------------------------------------- ERREURS

    @Test
    void evaluationInconnue_404_etRiskServiceHorsLigne_503() throws Exception {
        mvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + INCONNU + ",\"alertType\":\"OTHER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("evaluation introuvable chez le service distant : id = 999999"));

        mvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + HS + ",\"alertType\":\"OTHER\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Service distant indisponible : risk-service"));

        Long alertes = jdbc.queryForObject("SELECT COUNT(*) FROM alerts", Long.class);
        assertThat(alertes).isZero(); // ni 404 ni 503 n'ont rien ecrit
    }

    @Test
    void ressourcesInconnues_404() throws Exception {
        mvc.perform(patch("/api/alerts/999997/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Alerte introuvable : alertId = 999997"));

        mvc.perform(post("/api/alerts/999997/cases")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/cases/999996"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Case introuvable : caseId = 999996"));

        mvc.perform(post("/api/cases/999996/comments")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/cases/999996/comments"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/alerts/999995/case"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Case introuvable : alertId = 999995"));
    }

    @Test
    void transitionsInvalides_409_alerteEtCase() throws Exception {
        // hors graphe d'alerte
        long alertId = creerAlerte(ASSESSMENT);
        mvc.perform(patch("/api/alerts/" + alertId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FALSE_POSITIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Transition d'alerte illegale : OPEN -> FALSE_POSITIVE"));

        // alerte terminee -> plus d'ouverture de case
        changerStatutAlerte(alertId, "ACKNOWLEDGED");
        changerStatutAlerte(alertId, "FALSE_POSITIVE");
        mvc.perform(post("/api/alerts/" + alertId + "/cases")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("etat terminal")));

        // hors graphe de case (2e alerte/evaluation — compte ISOLE pour
        //    ne pas subir l'absorption d'agregation)
        long alerte2 = creerAlerte(ASSESSMENT3);
        ouvrirCase(alerte2);
        long case2 = caseId(alerte2);
        mvc.perform(patch("/api/cases/" + case2 + "/status")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Transition de case illegale : OPEN -> CONFIRMED"));
    }

    @Test
    void relecturesEtListes_200() throws Exception {
        long alertId = creerAlerte(ASSESSMENT);
        mvc.perform(get("/api/alerts/" + alertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertCode").value("ALT-900014"));
        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].alertId").value(alertId));

        mvc.perform(get("/api/alerts/" + alertId + "/case"))
                .andExpect(status().isNotFound());
        ouvrirCase(alertId);
        long caseId = caseId(alertId);
        mvc.perform(get("/api/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // journal : 1 creation d'alerte + 1 ouverture de case + 1 creation
        // de groupe (agregation M5 tracee a chaque alerte rattachee)
        mvc.perform(get("/api/audit").param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/audit").param("action", "CASE_OPENED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/audit").param("userId", Long.toString(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    // ------------------------------------------------------- AGREGATION M5

    /**
     * Jalon M5 sur finrisk_test : une alerte rattachee a son compte est
     * AGREEGEE (groupe + montant) et le groupe ESCALADE avec la seconde.
     * SQL des natives queries executee pour de vrai (pas de mock repo).
     */
    @Test
    void aggregation_uneAlertePuisEscaladeDeuxieme() throws Exception {
        // 1. premiere alerte -> groupe ouvert, montant copie
        mvc.perform(post("/api/alerts")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + ASSESSMENT
                                + ",\"alertType\":\"HIGH_AMOUNT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.amount").value(8500.00));

        long groupId = jdbc.queryForObject(
                "SELECT group_id FROM alerts WHERE assessment_id = ?", Long.class, ASSESSMENT);
        mvc.perform(get("/api/alert-groups/" + groupId)
                        .header("X-User-Id", Long.toString(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(8500.00))
                .andExpect(jsonPath("$.customerId").value(CUSTOMER))
                .andExpect(jsonPath("$.scopeType").value("ACCOUNT"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 2. seconde alerte -> MEME groupe, compteur a 2, mais ABSORBEE
        //    (modele « 10 transactions -> 1 alerte » : la 1re reste OPEN,
        //    les suivantes sortent de la file principale).
        mvc.perform(post("/api/alerts")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + ASSESSMENT2
                                + ",\"alertType\":\"OTHER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ABSORBED"))
                .andExpect(jsonPath("$.groupId").value(groupId));
        long groupId2 = jdbc.queryForObject(
                "SELECT group_id FROM alerts WHERE assessment_id = ?", Long.class, ASSESSMENT2);
        assertThat(groupId2).isEqualTo(groupId);
        // Le endpoint renvoie une Page Spring Data (objet avec content,
        // totalElements...), pas un tableau — on verifie via totalElements.
        mvc.perform(get("/api/alert-groups/" + groupId + "/alerts")
                        .header("X-User-Id", Long.toString(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // 3. liste des groupes ouverts : un seul
        mvc.perform(get("/api/alert-groups")
                        .header("X-User-Id", Long.toString(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 4. la file principale (sans filtre) ne montre QUE la 1re alerte :
        //    l'absorbee n'y figure pas (1 seule alerte visible).
        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
        // ... mais un filtre explicite la rend accessible (audit / debug).
        mvc.perform(get("/api/alerts").param("status", "ABSORBED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("ABSORBED"));
    }

    /**
     * L'agregation est defensif : si transaction-service est hors ligne,
     * l'alerte est quand meme creee (sans groupe) — la mise en alerte ne
     * depend jamais d'un service d'inventaire.
     */
    @Test
    void aggregation_transactionServiceHorsLigne_alerteSansGroupe() throws Exception {
        // Le vrai TransactionClient ne renvoie JAMAIS null : il leve
        // RemoteResourceNotFoundException (404) ou DownstreamUnavailableException
        // (panne). C'est ce comportement qu'on simule ici.
        when(transactionClient.getById(TX))
                .thenThrow(new DownstreamUnavailableException("transaction-service", null));

        mvc.perform(post("/api/alerts")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":" + ASSESSMENT
                                + ",\"alertType\":\"HIGH_AMOUNT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
        Long groupes = jdbc.queryForObject("SELECT COUNT(*) FROM alert_groups", Long.class);
        assertThat(groupes).isZero();
    }

    // ---------------------------------------------------- DECISION DE GROUPE

    /**
     * REFUSE (fraude confirmee) : TOUTES les transactions du groupe sont
     * annulees + rembourseees (client transaction-service MOCKE), le groupe
     * passe REFUSED, les alertes membres (y compris l'absorbee) RESOLVED.
     * Rejouable : une deuxieme decision -> 409.
     */
    @Test
    void decisionRefuse_annuleLesTransactionsEtClotureLeGroupe() throws Exception {
        creerAlerte(ASSESSMENT);   // OPEN — point d'entree
        creerAlerte(ASSESSMENT2);  // ABSORBED — meme compte
        long groupId = jdbc.queryForObject(
                "SELECT group_id FROM alerts WHERE assessment_id = ?", Long.class, ASSESSMENT);

        mvc.perform(patch("/api/alert-groups/" + groupId + "/decision")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REFUSE\",\"reason\":\"structuring confirme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUSED"))
                .andExpect(jsonPath("$.memberAlertIds.length()").value(2));

        // Chaque transaction du groupe annulee (les 2 alertes partagent TX,
        // l'annulation est idempotente : 2 appels = 2 no-op sur la meme tx).
        verify(transactionClient, times(2)).cancelAndRefund(TX);
        // Groupe REFUSED + alertes membres RESOLVED en base.
        String groupeStatut = jdbc.queryForObject(
                "SELECT status FROM alert_groups WHERE group_id = ?", String.class, groupId);
        assertThat(groupeStatut).isEqualTo("REFUSED");
        Long resolues = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE group_id = ? AND status = 'RESOLVED'",
                Long.class, groupId);
        assertThat(resolues).isEqualTo(2L);
        Long resolvedAt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE group_id = ? AND resolved_at IS NOT NULL",
                Long.class, groupId);
        assertThat(resolvedAt).isEqualTo(2L);

        // Une deuxieme decision -> 409 (deja tranche).
        mvc.perform(patch("/api/alert-groups/" + groupId + "/decision")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REFUSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("deja tranche")));
    }

    /**
     * ACCEPT (pattern legitime) : aucune transaction touchee, groupe
     * RESOLVED, alertes membres FALSE_POSITIVE.
     */
    @Test
    void decisionAccept_patternLegitime_sansRemboursement() throws Exception {
        creerAlerte(ASSESSMENT);
        creerAlerte(ASSESSMENT2);
        long groupId = jdbc.queryForObject(
                "SELECT group_id FROM alerts WHERE assessment_id = ?", Long.class, ASSESSMENT);

        mvc.perform(patch("/api/alert-groups/" + groupId + "/decision")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        verify(transactionClient, never()).cancelAndRefund(anyLong());
        Long fauxPositives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE group_id = ? AND status = 'FALSE_POSITIVE'",
                Long.class, groupId);
        assertThat(fauxPositives).isEqualTo(2L);
    }

    /** Decision inconnue -> 400 ; groupe inconnu -> 404. */
    @Test
    void decision_invalides_400Et404() throws Exception {
        creerAlerte(ASSESSMENT);
        long groupId = jdbc.queryForObject(
                "SELECT group_id FROM alerts WHERE assessment_id = ?", Long.class, ASSESSMENT);

        mvc.perform(patch("/api/alert-groups/" + groupId + "/decision")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"BOF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("BOF")));

        mvc.perform(patch("/api/alert-groups/999994/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REFUSE\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/alert-groups/" + groupId + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * REJOUABILITE : si transaction-service est hors ligne, la decision
     * REFUSE echoue (503) et le groupe reste OPEN — l'analyste peut rejouer
     * une fois le service revenu. Rien n'est cloture en local.
     */
    @Test
    void decisionRefuse_transactionServiceHorsLigne_groupeResteOpen() throws Exception {
        creerAlerte(ASSESSMENT);
        long groupId = jdbc.queryForObject(
                "SELECT group_id FROM alerts WHERE assessment_id = ?", Long.class, ASSESSMENT);
        when(transactionClient.cancelAndRefund(TX))
                .thenThrow(new DownstreamUnavailableException("transaction-service", null));

        mvc.perform(patch("/api/alert-groups/" + groupId + "/decision")
                        .header("X-User-Id", Long.toString(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REFUSE\"}"))
                .andExpect(status().isServiceUnavailable());

        String groupeStatut = jdbc.queryForObject(
                "SELECT status FROM alert_groups WHERE group_id = ?", String.class, groupId);
        assertThat(groupeStatut).isEqualTo("OPEN");
        String alerteStatut = jdbc.queryForObject(
                "SELECT status FROM alerts WHERE assessment_id = ?", String.class, ASSESSMENT);
        assertThat(alerteStatut).isEqualTo("OPEN");
    }
}
