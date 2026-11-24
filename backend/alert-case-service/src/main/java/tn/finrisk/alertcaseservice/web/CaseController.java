package tn.finrisk.alertcaseservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.CaseComment;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;
import tn.finrisk.alertcaseservice.service.CaseService;
import tn.finrisk.alertcaseservice.web.dto.CaseResponse;
import tn.finrisk.alertcaseservice.web.dto.CaseStatusRequest;
import tn.finrisk.alertcaseservice.web.dto.CommentCreateRequest;
import tn.finrisk.alertcaseservice.web.dto.CommentResponse;

/**
 * Dossiers d'enquete : transitions, fil de commentaires, cloture tracee.
 * X-User-Id est OBLIGATOIRE sur les commentaires (user_id NOT NULL) et
 * optionnel ailleurs — M5 branchera le vrai token.
 */
@Tag(name = "Cases", description = "M4 : enquete, commentaires et cloture des dossiers")
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    /** GET /api/cases?status=&page=0&size=20 */
    @Operation(summary = "Liste les cases", description = "Paginee, filtree par statut si fourni.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de cases"),
            @ApiResponse(responseCode = "400", description = "Parametre invalide")})
    @GetMapping
    public Page<CaseResponse> list(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return caseService.page(status, PageRequest.of(page, size)).map(CaseResponse::from);
    }

    /** GET /api/cases/{id} */
    @Operation(summary = "Consulter une case")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La case"),
            @ApiResponse(responseCode = "404", description = "Case inconnue")})
    @GetMapping("/{id}")
    public CaseResponse getById(@PathVariable Long id) {
        return CaseResponse.from(caseService.get(id));
    }

    /** PATCH /api/cases/{id}/status { "status": "UNDER_REVIEW" } */
    @Operation(summary = "Changer le statut d'une case",
            description = "Machine a etats CaseStatus : transition hors graphe -> 409 ; "
                    + "passage terminal (CLOSED / FALSE_POSITIVE) -> closedAt renseigne, "
                    + "investigationResult optionnel (50 caracteres max).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statut mis a jour (trace en audit_logs)"),
            @ApiResponse(responseCode = "404", description = "Case inconnue"),
            @ApiResponse(responseCode = "409", description = "Transition illegale ou case cloturee")})
    @PatchMapping("/{id}/status")
    public CaseResponse changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody CaseStatusRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        return CaseResponse.from(caseService.changeStatus(id, request, userId, ipAddress));
    }

    /** POST /api/cases/{id}/comments { "content": "...", "commentType": "EVIDENCE" } */
    @Operation(summary = "Ajouter un commentaire au fil d'enquete",
            description = "X-User-Id requis (auteur) ; interdit sur une case cloturee (409).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Commentaire cree (header Location)"),
            @ApiResponse(responseCode = "400", description = "X-User-Id manquant ou content vide"),
            @ApiResponse(responseCode = "404", description = "Case inconnue"),
            @ApiResponse(responseCode = "409", description = "Case cloturee")})
    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable Long id,
            @Valid @RequestBody CommentCreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress) {
        CaseComment comment = caseService.addComment(id, request.content(), request.commentType(),
                userId, ipAddress);
        return ResponseEntity
                .created(URI.create("/api/cases/" + id + "/comments/" + comment.getCommentId()))
                .body(CommentResponse.from(comment));
    }

    /** GET /api/cases/{id}/comments — fil chronologique. */
    @Operation(summary = "Lire le fil de commentaires d'une case",
            description = "Ordre chronologique d'ecriture ; 404 si la case n'existe pas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le fil"),
            @ApiResponse(responseCode = "404", description = "Case inconnue")})
    @GetMapping("/{id}/comments")
    public List<CommentResponse> comments(@PathVariable Long id) {
        return caseService.comments(id).stream().map(CommentResponse::from).toList();
    }
}
