package tn.finrisk.transactionservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.transactionservice.service.MerchantService;
import tn.finrisk.transactionservice.web.dto.MerchantCreateRequest;
import tn.finrisk.transactionservice.web.dto.MerchantResponse;

/** Marchands : table propriaire du transaction-service. */
@Tag(name = "Merchants", description = "Marchands (tiers recepteurs d'un paiement)")
@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @Operation(summary = "Lister les marchands")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Liste complete"))
    @GetMapping
    public List<MerchantResponse> getAll() {
        return merchantService.getAll();
    }

    @Operation(summary = "Consulter un marchand", description = "Retourne 404 si inconnu.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le marchand"),
            @ApiResponse(responseCode = "404", description = "Marchand introuvable")})
    @GetMapping("/{id}")
    public MerchantResponse getById(@PathVariable Long id) {
        return merchantService.getById(id);
    }

    @Operation(summary = "Creer un marchand", description = "Le nom doit etre unique (insensible a la casse).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Marchand cree (header Location)"),
            @ApiResponse(responseCode = "400", description = "Nom absent ou invalide"),
            @ApiResponse(responseCode = "409", description = "Nom deja utilise")})
    @PostMapping
    public ResponseEntity<MerchantResponse> create(@Valid @RequestBody MerchantCreateRequest request) {
        MerchantResponse created = merchantService.create(request);
        URI location = URI.create("/api/merchants/" + created.merchantId());
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Supprimer un marchand",
            description = "404 si inconnu. Les transactions liees conservent leur historique "
                    + "(merchant_id passe a NULL, fk_tx_merchant = ON DELETE SET NULL).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marchand supprime"),
            @ApiResponse(responseCode = "404", description = "Marchand introuvable")})
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        merchantService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
