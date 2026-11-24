package tn.finrisk.alertcaseservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.service.AlertService;
import tn.finrisk.alertcaseservice.service.CaseService;
import tn.finrisk.alertcaseservice.service.StatsService;
import tn.finrisk.alertcaseservice.web.dto.AlertCreateRequest;
import tn.finrisk.alertcaseservice.web.dto.AlertResponse;
import tn.finrisk.alertcaseservice.web.dto.AlertStatsResponse;
import tn.finrisk.alertcaseservice.web.dto.AlertStatusRequest;
import tn.finrisk.alertcaseservice.web.dto.CaseOpenRequest;
import tn.finrisk.alertcaseservice.web.dto.CaseResponse;
import tn.finrisk.alertcaseservice.web.dto.DeleteSummary;

/**
 * Façade des alertes : une evaluation (risk-service) devient une alerte,
 * l'alerte ouvre son dossier d'enquete. Les en-tetes X-User-Id /
 * X-Forwarded-For sont optionnels en M4 (pas d'authentification avant M5)
 * mais deja acceptes : ils alimentent la trace audit_logs.
 */
@Tag(name = "Alertes", description = "M4 : alertes issues des evaluations et ouverture des cases")
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;
    private final CaseService caseService;
    private final StatsService statsService;

    public AlertController(AlertService alertService, CaseService caseService,
            StatsService statsService) {
        this.alertService = alertService;
        this.caseService = caseService;
        this.statsService = statsService;
    }

    /** POST /api/alerts { "assessmentId": 42, "alertType": "HIGH_AMOUNT" } */
    @Operation(summary = "Cree une alerte depuis une evaluation",
            description = "L'evaluation est verifiee chez risk-service (404 si inconnue), "
                    + "une seule alerte par evaluation (409 sinon). alertCode = ALT-{assessmentId}. "
                    + "severity absente -> derivee du verdict du moteur.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Alerte creee (header Location)"),
            @ApiResponse(responseCode = "400", description = "assessmentId ou alertType manquant"),
            @ApiResponse(responseCode = "404", description = "Evaluation inconnue chez risk-service"),
            @ApiResponse(responseCode = "409", description = "Evaluation deja alertee"),
            @ApiResponse(responseCode = "503", description = "Risk-service injoignable")})
    @PostMapping
    public ResponseEntity<AlertResponse> create(
            @Valid @RequestBody AlertCreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        Alert alert = alertService.create(request, userId, ipAddress);
        return ResponseEntity
                .created(URI.create("/api/alerts/" + alert.getAlertId()))
                .body(AlertResponse.from(alert));
    }

    /** GET /api/alerts?status=&page=0&size=20 */
    @Operation(summary = "Liste les alertes", description = "Paginee, filtree par statut si fourni.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page d'alertes"),
            @ApiResponse(responseCode = "400", description = "Parametre invalide")})
    @GetMapping
    public Page<AlertResponse> list(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return alertService.page(status, PageRequest.of(page, size)).map(AlertResponse::from);
    }

    /**
     * GET /api/alerts/stats — KPI du dashboard SENTINEL (statut, severite,
     * cases, groupes, activite 7 j). Agregats cote base, chemins statiques
     * prioritaires sur /{id} en Spring MVC.
     */
    @Operation(summary = "KPI du dashboard",
            description = "Alertes par statut/severite, cases ouverts, groupes ouverts + cumul, "
                    + "montant absorbe, activite des 7 derniers jours (UTC).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KPI prets a afficher"),
            @ApiResponse(responseCode = "503", description = "Service de base de donnees injoignable")})
    @GetMapping("/stats")
    public AlertStatsResponse stats() {
        return statsService.dashboard();
    }

    /** GET /api/alerts/{id} */
    @Operation(summary = "Consulter une alerte")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "L'alerte"),
            @ApiResponse(responseCode = "404", description = "Alerte inconnue")})
    @GetMapping("/{id}")
    public AlertResponse getById(@PathVariable Long id) {
        return AlertResponse.from(alertService.get(id));
    }

    /** PATCH /api/alerts/{id}/status { "status": "ACKNOWLEDGED" } */
    @Operation(summary = "Changer le statut d'une alerte",
            description = "Machine a etats AlertStatus : transition hors graphe -> 409 ; "
                    + "passage terminal (RESOLVED / FALSE_POSITIVE) -> resolvedAt renseigne.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statut mis a jour (trace en audit_logs)"),
            @ApiResponse(responseCode = "404", description = "Alerte inconnue"),
            @ApiResponse(responseCode = "409", description = "Transition illegale")})
    @PatchMapping("/{id}/status")
    public AlertResponse changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody AlertStatusRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        return AlertResponse.from(alertService.changeStatus(id, request.status(), userId, ipAddress));
    }

    /** POST /api/alerts/{alertId}/cases */
    @Operation(summary = "Ouvrir le dossier d'enquete d'une alerte",
            description = "Une seule case par alerte (409 si deja ouverte) ; "
                    + "impossible d'en ouvrir sur une alerte terminee (409).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Case ouverte (header Location)"),
            @ApiResponse(responseCode = "404", description = "Alerte inconnue"),
            @ApiResponse(responseCode = "409", description = "Case deja ouverte ou alerte terminee")})
    @PostMapping("/{alertId}/cases")
    public ResponseEntity<CaseResponse> openCase(
            @PathVariable Long alertId,
            @RequestBody(required = false) CaseOpenRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        Case entity = caseService.open(alertId, request, userId, ipAddress);
        return ResponseEntity
                .created(URI.create("/api/cases/" + entity.getCaseId()))
                .body(CaseResponse.from(entity));
    }

    /** GET /api/alerts/{alertId}/case — la case unique (404 si aucune). */
    @Operation(summary = "Case rattachee a une alerte",
            description = "Relation 1:1 : 404 tant qu'aucune enquete n'a ete ouverte.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La case"),
            @ApiResponse(responseCode = "404", description = "Alerte ou case inconnue")})
    @GetMapping("/{alertId}/case")
    public CaseResponse getCase(@PathVariable Long alertId) {
        return CaseResponse.from(caseService.forAlert(alertId));
    }

    /** DELETE /api/alerts?customerId=42 */
    @Operation(summary = "Purger les alertes d'un client",
            description = "Enquetes (cases + commentaires) puis alertes, dans cet ordre "
                    + "(fk_cases_alert = RESTRICT). Trace laissee dans audit_logs. "
                    + "Premiere etape de la cascade de suppression d'un client.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre d'alertes supprimees")})
    @DeleteMapping
    public DeleteSummary deleteByCustomer(
            @RequestParam Long customerId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        return new DeleteSummary(alertService.deleteByCustomer(customerId, userId, ipAddress));
    }
}
