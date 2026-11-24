package tn.finrisk.alertcaseservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.AlertGroup;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.service.AlertGroupService;
import tn.finrisk.alertcaseservice.service.AlertService;
import tn.finrisk.alertcaseservice.web.dto.AlertGroupResponse;
import tn.finrisk.alertcaseservice.web.dto.AlertResponse;
import tn.finrisk.alertcaseservice.web.dto.GroupDecisionRequest;

/**
 * Lecture des GROUPEs d'alertes apparentees + decision de l'analyste.
 *
 * Un groupe est un AGREGAT : il n'a pas de creation manuelle, mais
 * l'analyste peut TRANCHER dessus (PATCH /{id}/decision) :
 *   ACCEPT — pattern legitime, alertes membres FALSE_POSITIVE ;
 *   REFUSE — fraude confirmee, transactions annulees + remboursees.
 * Le seul autre writer est AlertGroupService, appele a chaque creation
 * d'alerte (attach).
 */
@Tag(name = "Groupes d'alertes",
        description = "Agregation d'alertes apparentees (fractionnement / structuring) et decision")
@RestController
@RequestMapping("/api/alert-groups")
public class AlertGroupController {

    private final AlertGroupService groupService;
    private final AlertService alertService;

    public AlertGroupController(AlertGroupService groupService, AlertService alertService) {
        this.groupService = groupService;
        this.alertService = alertService;
    }

    /** GET /api/alert-groups */
    @Operation(summary = "Liste les groupes d'alertes encore ouverts",
            description = "Du plus fraichement mis a jour au plus ancien. "
                    + "Un groupe porte une severite AGREEGEE : montee avec le nombre "
                    + "d'alertes membres (>=5 au moins MEDIUM, >=10 au moins HIGH).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Groupes ouverts"),
            @ApiResponse(responseCode = "400", description = "Parametre invalide")})
    @GetMapping
    public List<AlertGroupResponse> list() {
        return groupService.openGroups().stream()
                .map(AlertGroupResponse::from)
                .toList();
    }

    /** GET /api/alert-groups/{id} */
    @Operation(summary = "Consulter un groupe et ses alertes membres")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le groupe"),
            @ApiResponse(responseCode = "404", description = "Groupe inconnu")})
    @GetMapping("/{id}")
    public AlertGroupResponse getById(@PathVariable Long id) {
        AlertGroup group = groupService.get(id);
        return AlertGroupResponse.from(group, groupService.memberAlertIds(id));
    }

    /**
     * GET /api/alert-groups/{id}/alerts — les alertes membres, avec leur
     * severite INDIVIDUELLE intacte. C'est la vue « detail du pattern » :
     * l'analyste voit le groupe escalade ET les evaluations qui le composent.
     */
    @Operation(summary = "Alertes membres d'un groupe",
            description = "Paginees, ordre chronologique de creation. "
                    + "La severite de chaque alerte est celle de SON evaluation, "
                    + "elle n'a pas ete modifiee par l'agregation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alertes membres"),
            @ApiResponse(responseCode = "404", description = "Groupe inconnu")})
    @GetMapping("/{id}/alerts")
    public Page<AlertResponse> members(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        groupService.get(id);
        List<Long> ids = groupService.memberAlertIds(id);
        // Les ids sont tries chronologiquement : on page dessus en memoire
        // (un groupe est borne par nature — 100 alertes en 24h est deja
        // exceptionnel, et l'index idx_alerts_group porte la lecture).
        int from = Math.min(page * size, ids.size());
        int to = Math.min(from + size, ids.size());
        List<Long> slice = ids.subList(from, to);
        List<AlertResponse> content = slice.stream()
                .map(alertId -> AlertResponse.from(alertService.get(alertId)))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(
                content, PageRequest.of(page, size), ids.size());
    }

    /**
     * PATCH /api/alert-groups/{id}/decision { "decision": "REFUSE", "reason": "..." }
     *
     * ACCEPT — pattern legitime : groupe RESOLVED, alertes membres
     *          FALSE_POSITIVE, aucune transaction touchee.
     * REFUSE — fraude confirmee : TOUTES les transactions du groupe sont
     *          annulees et remboursees (appels a transaction-service, qui
     *          regle le solde chez account-service), groupe REFUSED,
     *          alertes membres RESOLVED. Rejouable apres un echec partiel
     *          (annulation idempotente).
     */
    @Operation(summary = "Decision de l'analyste sur un groupe",
            description = "ACCEPT (pattern legitime) ou REFUSE (annulation + "
                    + "remboursement de toutes les transactions du groupe). "
                    + "Un groupe deja tranche -> 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Groupe tranche (trace en audit_logs)"),
            @ApiResponse(responseCode = "400", description = "decision manquante ou inconnue"),
            @ApiResponse(responseCode = "404", description = "Groupe inconnu"),
            @ApiResponse(responseCode = "409", description = "Groupe deja tranche"),
            @ApiResponse(responseCode = "503", description = "Service distant injoignable (rejouable)")})
    @PatchMapping("/{id}/decision")
    public AlertGroupResponse decide(
            @PathVariable Long id,
            @Valid @RequestBody GroupDecisionRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        AlertGroup group = groupService.decide(id, request.decision(), request.reason(),
                userId, ipAddress);
        return AlertGroupResponse.from(group, groupService.memberAlertIds(id));
    }
}
