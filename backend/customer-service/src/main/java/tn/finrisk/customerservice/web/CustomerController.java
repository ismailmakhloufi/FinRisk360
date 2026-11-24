package tn.finrisk.customerservice.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.service.AccountService;
import tn.finrisk.customerservice.service.CustomerService;
import tn.finrisk.customerservice.web.dto.AccountResponse;
import tn.finrisk.customerservice.web.dto.CustomerRequest;
import tn.finrisk.customerservice.web.dto.CustomerResponse;
import tn.finrisk.customerservice.web.dto.DeleteSummary;

/**
 * Point d'entree HTTP : il ne fait QUE router.
 *   - il parse la requete (JSON -> CustomerRequest)
 *   - il valide (@Valid -> 400 automatique)
 *   - il appelle le service
 *   - il met en forme la reponse (code + corps)
 * AUCUNE regle metier ici.
 */
@Tag(name = "Customers", description = "Creation et consultation des clients FinRisk360")
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final AccountService accountService;

    public CustomerController(CustomerService customerService, AccountService accountService) {
        this.customerService = customerService;
        this.accountService = accountService;
    }

    /** GET /api/customers?page=0&size=20&sort=customerId,asc */
    @Operation(summary = "Lister les clients", description = "Resultats pagines (20 par page au maximum).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de clients"),
            @ApiResponse(responseCode = "400", description = "Parametre page/size/sort invalide")})
    @GetMapping
    public Page<CustomerResponse> getAll(
            @Parameter(description = "Numero de page, a partir de 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Elements par page (1 a 100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Champ de tri : customerId, lastName, registrationDate...") @RequestParam(defaultValue = "customerId") String sort) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return customerService.getAll(PageRequest.of(page, safeSize, Sort.by(sort)));
    }

    /** GET /api/customers/{id} */
    @Operation(summary = "Consulter un client", description = "Retourne 404 si l'identifiant n'existe pas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le client"),
            @ApiResponse(responseCode = "404", description = "Client introuvable")})
    @GetMapping("/{id}")
    public CustomerResponse getById(@PathVariable Long id) {
        return customerService.getById(id);
    }

    /** GET /api/customers/{id}/accounts */
    @Operation(summary = "Comptes d'un client", description = "Liste triee par compte le plus recent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des comptes (vide si aucun)"),
            @ApiResponse(responseCode = "404", description = "Client introuvable")})
    @GetMapping("/{id}/accounts")
    public List<AccountResponse> getAccounts(@PathVariable Long id) {
        return accountService.getAccountsOf(id);
    }

    /** POST /api/customers -> 201 Created + header Location */
    @Operation(summary = "Creer un client", description = "L'email doit etre unique ; il est optionnel.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Client cree (header Location)"),
            @ApiResponse(responseCode = "400", description = "Donnees invalides, detail dans errors[]"),
            @ApiResponse(responseCode = "409", description = "Email deja utilise")})
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        URI location = URI.create("/api/customers/" + created.customerId());
        return ResponseEntity.created(location).body(created);
    }

    /** PUT /api/customers/{id} */
    @Operation(summary = "Modifier un client", description = "Remplace les champs fournis ; 409 si l'email appartient a un autre client.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Client mis a jour"),
            @ApiResponse(responseCode = "400", description = "Donnees invalides"),
            @ApiResponse(responseCode = "404", description = "Client introuvable"),
            @ApiResponse(responseCode = "409", description = "Email deja utilise")})
    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(id, request);
    }

    /** PATCH /api/customers/{id}/status?status=SUSPENDED -> 200 */
    @Operation(summary = "Changer le statut", description = "ACTIVE, SUSPENDED ou CLOSED. Pas de suppression dure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statut modifie"),
            @ApiResponse(responseCode = "400", description = "Statut inconnu"),
            @ApiResponse(responseCode = "404", description = "Client introuvable")})
    @PatchMapping("/{id}/status")
    public CustomerResponse changeStatus(@PathVariable Long id,
                                         @RequestParam CustomerStatus status) {
        return customerService.changeStatus(id, status);
    }

    /** DELETE /api/customers/{id} */
    @Operation(summary = "Supprimer definitivement un client",
            description = "Cascade cote appelant dans l'ordre FK : alerts -> evaluations -> "
                    + "transactions -> accounts -> customers (risque : risk_profiles en CASCADE). "
                    + "404 si inconnu.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Supprime (nombre de lignes)"),
            @ApiResponse(responseCode = "404", description = "Client introuvable")})
    @DeleteMapping("/{id}")
    public DeleteSummary delete(@PathVariable Long id) {
        return new DeleteSummary(customerService.delete(id));
    }
}
