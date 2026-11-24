package tn.finrisk.riskservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.riskservice.client.AlertClient;
import tn.finrisk.riskservice.client.DownstreamUnavailableException;
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskCategory;
import tn.finrisk.riskservice.domain.enums.RiskLevel;
import tn.finrisk.riskservice.domain.enums.Severity;
import tn.finrisk.riskservice.service.EvaluationService;
import tn.finrisk.riskservice.web.dto.AssessmentResponse;
import tn.finrisk.riskservice.web.dto.RuleResponse;
import tn.finrisk.riskservice.web.error.AssessmentNotFoundException;

/**
 * Couche Web seule (MockMvc + service MOCKE) : codes HTTP, headers, contraintes
 * de validation et mappage des exceptions via le GlobalExceptionHandler.
 * Aucune base, aucun reseau.
 */
@WebMvcTest({EvaluationController.class, RuleController.class})
class EvaluationApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EvaluationService evaluationService;
    /** La creation d'alerte part vers alert-case-service : hors reseau ici. */
    @MockitoBean
    private AlertClient alertClient;
    @MockitoBean
    private tn.finrisk.riskservice.service.StatsService statsService;

    @Test
    void stats_routeStatique_200() throws Exception {
        when(statsService.dashboard()).thenReturn(
                new tn.finrisk.riskservice.web.dto.EvaluationStatsResponse(
                        120,
                        java.util.Map.of("APPROVED", 90L, "HOLD", 5L),
                        java.util.Map.of("LOW", 100L, "HIGH", 8L),
                        new BigDecimal("23.4"),
                        new BigDecimal("97.0"),
                        List.of(new tn.finrisk.riskservice.web.dto.EvaluationStatsResponse
                                .ProfileDigest(7L, "HIGH", new BigDecimal("500"),
                                        new BigDecimal("15000"), "TN", 14, 90))));

        mvc.perform(get("/api/evaluations/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAssessments").value(120))
                .andExpect(jsonPath("$.byDecision.APPROVED").value(90))
                .andExpect(jsonPath("$.byRiskLevel.HIGH").value(8))
                .andExpect(jsonPath("$.avgScore").value(23.4))
                .andExpect(jsonPath("$.watchlist[0].customerId").value(7))
                .andExpect(jsonPath("$.watchlist[0].profileRiskLevel").value("HIGH"));
    }

    private static final AssessmentResponse REPONSE = new AssessmentResponse(
            7L, 42L, new BigDecimal("12.50"), RiskLevel.LOW, Decision.APPROVED,
            OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1", List.of());

    @Test
    void postEvaluation_renvoie201AvecLocation() throws Exception {
        when(evaluationService.evaluate(42L)).thenReturn(REPONSE);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":42}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/evaluations/7"))
                .andExpect(jsonPath("$.assessmentId").value(7))
                .andExpect(jsonPath("$.transactionId").value(42))
                .andExpect(jsonPath("$.riskScore").value(12.50))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.engineVersion").value("v1"));
    }

    @Test
    void postEvaluation_ouvreUneAlertePourLaTrace() throws Exception {
        when(evaluationService.evaluate(42L)).thenReturn(REPONSE);

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":42}"))
                .andExpect(status().isCreated());

        // Toute transaction doit laisser une alerte, meme un verdict APPROVED :
        // sinon elle serait liquidee sans qu'un analyste ne l'ait jamais vue.
        verify(alertClient).createAlert(7L, "OTHER");
    }

    @Test
    void postEvaluation_transactionIdManquant_400() throws Exception {
        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Donnees invalides"))
                .andExpect(jsonPath("$.errors[0].field").value("transactionId"));

        verify(evaluationService, never()).evaluate(any());
        verify(alertClient, never()).createAlert(any(), any());
    }

    @Test
    void postEvaluation_corpsIllisible_400() throws Exception {
        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("pas du json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Corps de requete illisible ou JSON invalide"));
    }

    @Test
    void getEvaluationInconnue_404() throws Exception {
        when(evaluationService.getAssessment(999L))
                .thenThrow(new AssessmentNotFoundException("assessmentId", 999L));

        mvc.perform(get("/api/evaluations/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Evaluation introuvable : assessmentId = 999"));
    }

    @Test
    void amontIndisponible_503() throws Exception {
        when(evaluationService.evaluate(5L))
                .thenThrow(new DownstreamUnavailableException("transaction-service", null));

        mvc.perform(post("/api/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":5}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Service distant indisponible : transaction-service"));
    }

    @Test
    void listeRegles_200() throws Exception {
        when(evaluationService.listRules()).thenReturn(List.of(new RuleResponse(
                1L, "RULE-001", "Montant eleve", RiskCategory.AMOUNT, ">",
                new BigDecimal("5000"), new BigDecimal("0.25"), Severity.HIGH, true)));

        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleCode").value("RULE-001"))
                .andExpect(jsonPath("$[0].weight").value(0.25))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void modificationRegle_poidsHorsBornes_400() throws Exception {
        mvc.perform(put("/api/rules/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight\":2}"))
                .andExpect(status().isBadRequest());

        verify(evaluationService, never()).updateRule(any(), any());
    }

    @Test
    void modificationRegle_renvoieLaRegle() throws Exception {
        when(evaluationService.updateRule(any(), any())).thenReturn(new RuleResponse(
                1L, "RULE-001", "Montant eleve", RiskCategory.AMOUNT, ">",
                new BigDecimal("5000"), new BigDecimal("0.30"), Severity.HIGH, false));

        mvc.perform(put("/api/rules/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight\":0.30,\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(0.30))
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
