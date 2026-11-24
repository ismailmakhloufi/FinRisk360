package tn.finrisk.alertcaseservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.alertcaseservice.service.AuditService;
import tn.finrisk.alertcaseservice.web.dto.AuditResponse;

/**
 * Journal d'audit (audit_logs) — la trace exigee par le jalon M4 :
 * chaque mutation d'alerte/case/commentaire s'y trouve, dans l'ordre.
 *
 * Deux vues :
 *  - journal general paginé (filtres userId OU action) ;
 *  - trace COMPLETE d'une entite (entityType + entityId) : « que s'est-il
 *    passe sur ce dossier ? » — la reponse de la soutenance.
 */
@Tag(name = "Audit", description = "M4 : trace des mutations (alertes, cases, commentaires)")
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** GET /api/audit?userId=&action=&page=0&size=20  — ou —  ?entityType=CASE&entityId=7 */
    @Operation(summary = "Consulter le journal d'audit",
            description = "Avec entityType+entityId : trace complete et chronologique d'une entite. "
                    + "Sans : journal paginé, du plus recent au plus ancien "
                    + "(filtres userId ou action optionnels).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Journal (liste ou page)"),
            @ApiResponse(responseCode = "400", description = "Parametre invalide")})
    @GetMapping
    public Object search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (entityType != null && entityId != null) {
            List<AuditResponse> trace = auditService.byEntity(entityType, entityId);
            return trace;
        }
        return auditService.page(userId, action, PageRequest.of(page, size));
    }
}
