package tn.finrisk.accountservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
import tn.finrisk.accountservice.domain.enums.AccountStatus;
import tn.finrisk.accountservice.service.AccountService;
import tn.finrisk.accountservice.web.dto.AccountCreateRequest;
import tn.finrisk.accountservice.web.dto.AccountResponse;
import tn.finrisk.accountservice.web.dto.BalanceOperationRequest;
import tn.finrisk.accountservice.web.dto.DeleteSummary;

/**
 * Point d'entree HTTP : il ne fait QUE router.
 * AUCUNE regle metier ici (voir AccountService).
 */
@Tag(name = "Accounts", description = "Comptes bancaires FinRisk360 : creation, consultation, solde")
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** GET /api/accounts?page=0&size=20&customerId=42 */
    @Operation(summary = "Lister les comptes",
            description = "Resultats pagines ; filtrables par clientId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de comptes"),
            @ApiResponse(responseCode = "400", description = "Parametre page/size/sort/customerId invalide")})
    @GetMapping
    public Page<AccountResponse> getAll(
            @Parameter(description = "Numero de page, a partir de 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Elements par page (1 a 100)")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Champ de tri : accountId, balance, openingDate...")
            @RequestParam(defaultValue = "accountId") String sort,
            @Parameter(description = "Filtrer par client")
            @RequestParam(required = false) Long customerId) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return accountService.getAll(PageRequest.of(page, safeSize, Sort.by(sort)), customerId);
    }

    /** GET /api/accounts/{id} */
    @Operation(summary = "Consulter un compte", description = "Retourne 404 si l'identifiant n'existe pas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le compte"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")})
    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable Long id) {
        return accountService.getById(id);
    }

    /** GET /api/accounts/number/{number} */
    @Operation(summary = "Consulter par numero de compte", description = "Recherche unique sur account_number.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le compte"),
            @ApiResponse(responseCode = "404", description = "Numero inconnu")})
    @GetMapping("/number/{number}")
    public AccountResponse getByNumber(@PathVariable String number) {
        return accountService.getByNumber(number);
    }

    /** GET /api/accounts/rib/{rib} */
    @Operation(summary = "Resolution d'un RIB",
            description = "RIB bien forme d'un compte de la banque -> 200 (beneficiaire interne). "
                    + "RIB d'une autre banque ou inconnu -> 404 (beneficiaire externe). "
                    + "RIB malforme (longueur ou cle modulo 97) -> 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le compte designe par le RIB"),
            @ApiResponse(responseCode = "400", description = "RIB malforme"),
            @ApiResponse(responseCode = "404", description = "Aucun compte a ce RIB (autre banque)")})
    @GetMapping("/rib/{rib}")
    public AccountResponse getByRib(@PathVariable String rib) {
        return accountService.getByRib(rib);
    }

    /** POST /api/accounts -> 201 Created + Location */
    @Operation(summary = "Creer un compte",
            description = "Le numero est genere par le serveur s'il n'est pas fourni ; "
                    + "le solde initial doit etre positif ou nul.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Compte cree (header Location)"),
            @ApiResponse(responseCode = "400", description = "Donnees invalides, detail dans errors[]"),
            @ApiResponse(responseCode = "409", description = "Numero deja utilise ou client inconnu (FK)")})
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody AccountCreateRequest request) {
        AccountResponse created = accountService.create(request);
        URI location = URI.create("/api/accounts/" + created.accountId());
        return ResponseEntity.created(location).body(created);
    }

    /** PATCH /api/accounts/{id}/status?status=FROZEN */
    @Operation(summary = "Changer le statut",
            description = "ACTIVE, FROZEN ou CLOSED. CLOSED est definitif (aucune operation ulterieure).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statut modifie"),
            @ApiResponse(responseCode = "400", description = "Statut inconnu"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable"),
            @ApiResponse(responseCode = "409", description = "Compte deja cloture")})
    @PatchMapping("/{id}/status")
    public AccountResponse changeStatus(@PathVariable Long id,
                                        @RequestParam AccountStatus status) {
        return accountService.changeStatus(id, status);
    }

    /** PATCH /api/accounts/{id}/balance {amount, direction} */
    @Operation(summary = "Crediter ou debiter",
            description = "Solde verrouille pendant l'operation. Un DEBIT superieur au solde renvoie 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nouveau solde"),
            @ApiResponse(responseCode = "400", description = "Montant <= 0 ou direction inconnue"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable"),
            @ApiResponse(responseCode = "409", description = "Compte non actif ou solde insuffisant")})
    @PatchMapping("/{id}/balance")
    public AccountResponse operateBalance(@PathVariable Long id,
                                          @Valid @RequestBody BalanceOperationRequest request) {
        return accountService.operateBalance(id, request);
    }

    /** DELETE /api/accounts?customerId=42 */
    @Operation(summary = "Supprimer les comptes d'un client",
            description = "Cascade cote appelant : les transactions doivent avoir ete purgees "
                    + "au prealable (fk_tx_account = ON DELETE RESTRICT).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre de comptes supprimes"),
            @ApiResponse(responseCode = "409", description = "Transactions restantes sur le compte")})
    @DeleteMapping
    public DeleteSummary deleteByCustomer(@RequestParam Long customerId) {
        return new DeleteSummary(accountService.deleteByCustomer(customerId));
    }
}
