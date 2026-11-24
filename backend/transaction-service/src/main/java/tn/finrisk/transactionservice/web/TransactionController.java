package tn.finrisk.transactionservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.service.TransactionService;
import tn.finrisk.transactionservice.web.dto.DeleteSummary;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;

/**
 * Façade REST/JSON des transactions (consommee par le futur Angular).
 * La meme logique est exposee en SOAP par TransactionSoapEndpoint.
 */
@Tag(name = "Transactions", description = "Transactions bancaires FinRisk360 : creation, consultation, statut")
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final tn.finrisk.transactionservice.service.StatsService statsService;

    public TransactionController(TransactionService transactionService,
            tn.finrisk.transactionservice.service.StatsService statsService) {
        this.transactionService = transactionService;
        this.statsService = statsService;
    }

    /**
     * GET /api/transactions/stats?days=N — KPI du dashboard SENTINEL (top
     * beneficiaires, activite sur N jours + tendance, volume, type/canal).
     * Chemin statique prioritaire sur /{id} en Spring MVC.
     */
    @Operation(summary = "KPI du dashboard",
            description = "Top 8 beneficiaires, activite des N derniers jours (defaut 7, "
                    + "max 90, jours UTC) avec tendance vs la periode precedente, "
                    + "volume, et repartition par type et par canal.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KPI prets a afficher"),
            @ApiResponse(responseCode = "503", description = "Base de donnees injoignable")})
    @GetMapping("/stats")
    public tn.finrisk.transactionservice.web.dto.TransactionStatsResponse stats(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return statsService.dashboard(days);
    }

    /** GET /api/transactions?page&size&accountId&status&since&beneficiary&customerId&until */
    @Operation(summary = "Lister les transactions",
            description = "Filtrable par compte, statut, date (du/un), beneficiaire "
                    + "(sous-chaine, insensible a la casse) et/ou client ; resultats "
                    + "pagines. Tri : champ ASC par defaut, prefixe \"-\" pour DESC "
                    + "(ex. -transactionDate).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de transactions"),
            @ApiResponse(responseCode = "400", description = "Parametre invalide")})
    @GetMapping
    public Page<TransactionResponse> getAll(
            @Parameter(description = "Numero de page, a partir de 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Elements par page (1 a 100)")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Champ de tri : transactionId, amount, transactionDate... ; prefixe \"-\" = DESC")
            @RequestParam(defaultValue = "transactionId") String sort,
            @Parameter(description = "Filtrer par compte")
            @RequestParam(required = false) Long accountId,
            @Parameter(description = "Filtrer par statut")
            @RequestParam(required = false) TransactionStatus status,
            @Parameter(description = "Uniquement les transactions a partir de cette date (ISO-8601, ex. 2026-07-06) ; interpretee en UTC",
                    example = "2026-07-06")
            @RequestParam(required = false) LocalDate since,
            @Parameter(description = "Beneficiaire contenant cette chaine (insensible a la casse)",
                    example = "Trabelsi")
            @RequestParam(required = false) String beneficiary,
            @Parameter(description = "Filtrer par client (tous ses comptes)")
            @RequestParam(required = false) Long customerId,
            @Parameter(description = "Uniquement les transactions jusqu'a cette date (jour inclusif, UTC)",
                    example = "2026-10-12")
            @RequestParam(required = false) LocalDate until) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        String field = sort.startsWith("-") ? sort.substring(1) : sort;
        Sort ordering = sort.startsWith("-") ? Sort.by(Sort.Direction.DESC, field) : Sort.by(field);
        return transactionService.getAll(
                PageRequest.of(page, safeSize, ordering),
                accountId, status, since, beneficiary, customerId, until);
    }

    /** GET /api/transactions/{id} */
    @Operation(summary = "Consulter une transaction", description = "Retourne 404 si l'identifiant n'existe pas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La transaction"),
            @ApiResponse(responseCode = "404", description = "Transaction introuvable")})
    @GetMapping("/{id}")
    public TransactionResponse getById(@PathVariable Long id) {
        return transactionService.getById(id);
    }

    /** POST /api/transactions -> 201 Created + Location */
    @Operation(summary = "Enregistrer une transaction",
            description = "Nait en statut PENDING. Le compte doit exister (FK) ; "
                    + "si merchantId est fourni, le marchand doit etre connu.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction enregistree (header Location)"),
            @ApiResponse(responseCode = "400", description = "Donnees invalides, detail dans errors[]"),
            @ApiResponse(responseCode = "404", description = "Marchand inconnu"),
            @ApiResponse(responseCode = "409", description = "Compte inconnu (FK) ou contrainte violee")})
    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionCreateRequest request) {
        TransactionResponse created = transactionService.create(request);
        URI location = URI.create("/api/transactions/" + created.transactionId());
        return ResponseEntity.created(location).body(created);
    }

    /** PATCH /api/transactions/{id}/status?status=COMPLETED */
    @Operation(summary = "Changer le statut",
            description = "PENDING / HELD -> COMPLETED / FAILED / HELD / CANCELLED. "
                    + "COMPLETED -> CANCELLED uniquement : annulation avec "
                    + "REMBOURSEMENT (reversement du solde, usage : refus de "
                    + "groupe = fraude confirmee). CANCELLED -> CANCELLED : "
                    + "idempotent (rejouable). FAILED est definitif.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statut modifie"),
            @ApiResponse(responseCode = "400", description = "Statut inconnu"),
            @ApiResponse(responseCode = "404", description = "Transaction introuvable"),
            @ApiResponse(responseCode = "409", description = "Transaction deja terminee (hors COMPLETED -> CANCELLED)"),
            @ApiResponse(responseCode = "503", description = "Account-service injoignable (remboursement) : transaction non modifiee")})
    @PatchMapping("/{id}/status")
    public TransactionResponse changeStatus(@PathVariable Long id,
                                            @RequestParam TransactionStatus status) {
        return transactionService.changeStatus(id, status);
    }

    /** DELETE /api/transactions?customerId=42 */
    @Operation(summary = "Supprimer les transactions d'un client",
            description = "A faire AVANT la suppression des comptes (fk_tx_account = RESTRICT) ; "
                    + "chaque evaluation associee sera purgee ensuite chez risk-service.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre de transactions supprimees"),
            @ApiResponse(responseCode = "404", description = "Client inconnu")})
    @DeleteMapping
    public DeleteSummary deleteByCustomer(@RequestParam Long customerId) {
        return new DeleteSummary(transactionService.deleteByCustomer(customerId));
    }
}
