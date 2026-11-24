package tn.finrisk.alertcaseservice.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.CaseComment;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.CasePriority;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;
import tn.finrisk.alertcaseservice.domain.enums.CommentType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.service.AlertService;
import tn.finrisk.alertcaseservice.service.AuditService;
import tn.finrisk.alertcaseservice.service.CaseService;
import tn.finrisk.alertcaseservice.web.dto.AlertStatsResponse;
import tn.finrisk.alertcaseservice.web.dto.AuditResponse;
import tn.finrisk.alertcaseservice.web.error.AlertNotFoundException;
import tn.finrisk.alertcaseservice.web.error.BadRequestException;
import tn.finrisk.alertcaseservice.web.error.CaseNotFoundException;
import tn.finrisk.alertcaseservice.web.error.ConflictException;

/**
 * Couche Web seule (MockMvc + services MOCKES) : codes HTTP, headers,
 * contraintes de validation et mappage des exceptions via le
 * GlobalExceptionHandler. Aucune base, aucun reseau.
 */
@WebMvcTest({AlertController.class, CaseController.class, AuditController.class})
class AlertApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlertService alertService;
    @MockitoBean
    private CaseService caseService;
    @MockitoBean
    private AuditService auditService;
    @MockitoBean
    private tn.finrisk.alertcaseservice.service.StatsService statsService;

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");

    private static Alert alert(long id, AlertStatus status) {
        Alert alert = new Alert();
        alert.setAlertId(id);
        alert.setAssessmentId(42L);
        alert.setAlertCode("ALT-42");
        alert.setAlertType(AlertType.HIGH_AMOUNT);
        alert.setSeverity(Severity.HIGH);
        alert.setStatus(status);
        alert.setCreatedAt(T);
        return alert;
    }

    private static Case entity(long id, CaseStatus status) {
        Case entity = new Case();
        entity.setCaseId(id);
        entity.setAlertId(7L);
        entity.setAssignedTo(100L);
        entity.setStatus(status);
        entity.setPriority(CasePriority.MEDIUM);
        entity.setOpenedAt(T);
        return entity;
    }

    // --------------------------------------------------------------- ALERTES

    @Test
    void creationAlerte_201AvecLocation() throws Exception {
        when(alertService.create(any(), isNull(), isNull())).thenReturn(alert(42L, AlertStatus.OPEN));

        mvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":42,\"alertType\":\"HIGH_AMOUNT\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/alerts/42"))
                .andExpect(jsonPath("$.alertId").value(42))
                .andExpect(jsonPath("$.alertCode").value("ALT-42"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void creationAlerte_400ChampManquant() throws Exception {
        mvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertType\":\"HIGH_AMOUNT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Donnees invalides"))
                .andExpect(jsonPath("$.errors[0].field").value("assessmentId"));
    }

    @Test
    void creationAlerte_404EvaluationInconnueEt503HorsLigne() throws Exception {
        when(alertService.create(any(), isNull(), isNull()))
                .thenThrow(new RemoteResourceNotFoundException("evaluation", 42L));
        mvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":42,\"alertType\":\"OTHER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("evaluation introuvable chez le service distant : id = 42"));

        // doThrow : re-stub sans RE-INVOQUER le stub any() precedent
        doThrow(new DownstreamUnavailableException("risk-service", null))
                .when(alertService).create(any(), isNull(), isNull());
        mvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":42,\"alertType\":\"OTHER\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Service distant indisponible : risk-service"));
    }

    @Test
    void changementStatut_200_et_409_et_404() throws Exception {
        when(alertService.changeStatus(7L, AlertStatus.ACKNOWLEDGED, 100L, null))
                .thenReturn(alert(7L, AlertStatus.ACKNOWLEDGED));
        mvc.perform(patch("/api/alerts/7/status")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        verify(alertService).changeStatus(7L, AlertStatus.ACKNOWLEDGED, 100L, null);

        // doThrow : re-stub sans RE-INVOQUER le stub any() precedent
        doThrow(new ConflictException("Transition d'alerte illegale : OPEN -> RESOLVED"))
                .when(alertService).changeStatus(any(), any(), any(), any());
        mvc.perform(patch("/api/alerts/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Transition d'alerte illegale : OPEN -> RESOLVED"));

        doThrow(new AlertNotFoundException("alertId", 999L))
                .when(alertService).changeStatus(any(), any(), any(), any());
        mvc.perform(patch("/api/alerts/999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Alerte introuvable : alertId = 999"));
    }

    @Test
    void listeAlertes_200Paginee() throws Exception {
        when(alertService.page(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert(42L, AlertStatus.OPEN))));

        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].alertId").value(42))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ----------------------------------------------------------------- CASES

    @Test
    void ouvertureCase_201AvecLocation() throws Exception {
        when(caseService.open(any(), any(), isNull(), isNull()))
                .thenReturn(entity(7L, CaseStatus.OPEN));

        mvc.perform(post("/api/alerts/42/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/cases/7"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
    }

    @Test
    void consultationCaseInconnue_404() throws Exception {
        when(caseService.get(999L)).thenThrow(new CaseNotFoundException("caseId", 999L));

        mvc.perform(get("/api/cases/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Case introuvable : caseId = 999"));
    }

    // ---------------------------------------------------------- COMMENTAIRES

    @Test
    void commentaire_400SansAuteurEt400ContentVide() throws Exception {
        when(caseService.addComment(any(), any(), any(), isNull(), isNull()))
                .thenThrow(new BadRequestException("Header X-User-Id requis : un commentaire doit etre attribue"));
        mvc.perform(post("/api/cases/7/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"anonyme\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Header X-User-Id requis : un commentaire doit etre attribue"));

        // Content vide : rejete par la validation AVANT d'atteindre le service
        mvc.perform(post("/api/cases/7/comments")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Donnees invalides"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
    }

    @Test
    void commentaire_201AvecLocation() throws Exception {
        CaseComment comment = new CaseComment();
        comment.setCommentId(3L);
        comment.setCaseId(7L);
        comment.setUserId(100L);
        comment.setContent("Virement suspect");
        comment.setCommentType(CommentType.EVIDENCE);
        comment.setCreatedAt(T);
        when(caseService.addComment(any(), any(), any(), any(), any())).thenReturn(comment);

        mvc.perform(post("/api/cases/7/comments")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Virement suspect\",\"commentType\":\"EVIDENCE\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/cases/7/comments/3"))
                .andExpect(jsonPath("$.commentType").value("EVIDENCE"))
                .andExpect(jsonPath("$.userId").value(100));
    }

    @Test
    void commentaire_caseCloturee_409() throws Exception {
        when(caseService.addComment(any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("Case cloturee : plus aucun commentaire (CLOSED)"));

        mvc.perform(post("/api/cases/7/comments")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"trop tard\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Case cloturee")));
    }

    // ---------------------------------------------------------------- AUDIT

    @Test
    void audit_parEntite_200Liste() throws Exception {
        when(auditService.byEntity("CASE", 7L)).thenReturn(List.of(
                new AuditResponse(1L, 100L, "CASE_OPENED", "CASE", 7L, null, "OPEN", T, null)));

        mvc.perform(get("/api/audit").param("entityType", "CASE").param("entityId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CASE_OPENED"))
                .andExpect(jsonPath("$[0].newValue").value("OPEN"));
    }

    @Test
    void audit_pagines_200() throws Exception {
        when(auditService.page(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new AuditResponse(1L, 100L, "ALERT_CREATED", "ALERT", 7L, null,
                                "OPEN", T, null))));

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("ALERT_CREATED"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void pageAvecParametreInvalide_400() throws Exception {
        mvc.perform(get("/api/alerts").param("page", "pas-un-nombre"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("page")));
    }

    // ---------------------------------------------------------------- STATS

    @Test
    void stats_200EtRouteStatiquePrioritaireSurId() throws Exception {
        // GET /api/alerts/stats ne doit PAS tomber dans /{id} (Long) : le
        // chemin statique prime en Spring MVC.
        when(statsService.dashboard()).thenReturn(new AlertStatsResponse(
                42,
                java.util.Map.of("OPEN", 5L, "ABSORBED", 8L),
                java.util.Map.of("HIGH", 12L),
                10, 3, 2,
                new java.math.BigDecimal("7500"),
                new java.math.BigDecimal("1200.50"),
                7, 4, 25.5,
                List.of(new AlertStatsResponse.DayCount("2026-10-12", 4))));

        mvc.perform(get("/api/alerts/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAlerts").value(42))
                .andExpect(jsonPath("$.byStatus.OPEN").value(5))
                .andExpect(jsonPath("$.bySeverity.HIGH").value(12))
                .andExpect(jsonPath("$.openCases").value(3))
                .andExpect(jsonPath("$.openGroups").value(2))
                .andExpect(jsonPath("$.absorbedAmount").value(1200.50))
                .andExpect(jsonPath("$.activeHighCritical").value(7))
                .andExpect(jsonPath("$.closedAlerts").value(4))
                .andExpect(jsonPath("$.avgResolutionMinutes").value(25.5))
                .andExpect(jsonPath("$.activity7d[0].day").value("2026-10-12"))
                .andExpect(jsonPath("$.activity7d[0].count").value(4));
    }
}
