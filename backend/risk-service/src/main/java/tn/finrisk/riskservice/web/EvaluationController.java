package tn.finrisk.riskservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.riskservice.client.AlertClient;
import tn.finrisk.riskservice.service.EvaluationService;
import tn.finrisk.riskservice.web.dto.AssessmentResponse;
import tn.finrisk.riskservice.web.dto.DeleteSummary;
import tn.finrisk.riskservice.web.dto.EvaluationRequest;

/**
 * Façade du moteur de risque. L'APPELANT orchestre (choix d'archi M3) :
 * creer la transaction (8083) puis appeler POST /api/evaluations ici.
 * En M7, le bouton Angular enchaînera ces deux appels.
 */
@Tag(name = "Evaluations", description = "Moteur de risque : features, regles, score, decision")
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final AlertClient alertClient;
    private final tn.finrisk.riskservice.service.StatsService statsService;

    public EvaluationController(EvaluationService evaluationService, AlertClient alertClient,
            tn.finrisk.riskservice.service.StatsService statsService) {
        this.evaluationService = evaluationService;
        this.alertClient = alertClient;
        this.statsService = statsService;
    }

    /**
     * GET /api/evaluations/stats — KPI du dashboard SENTINEL (decisions,
     * niveaux de risque, scores, watchlist des profils 90 j). Chemin
     * statique prioritaire sur /{id} en Spring MVC.
     */
    @Operation(summary = "KPI du dashboard",
            description = "Repartition decisions/risques, score moyen et max, et les 6 "
                    + "profils de risque (baseline 90 jours) les plus eleves.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KPI prets a afficher"),
            @ApiResponse(responseCode = "503", description = "Base de donnees injoignable")})
    @GetMapping("/stats")
    public tn.finrisk.riskservice.web.dto.EvaluationStatsResponse stats() {
        return statsService.dashboard();
    }

    /** POST /api/evaluations { "transactionId": 42 } */
    @Operation(summary = "Evaluer une transaction",
            description = "Pipeline complet : historique 90j (tous comptes) -> regles -> "
                    + "score 0-100 -> decision ; persiste l'assessment, ses facteurs "
                    + "explicatifs, rafraichit le profil du client et ouvre UNE ALERTE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evaluation creee (header Location)"),
            @ApiResponse(responseCode = "400", description = "transactionId manquant"),
            @ApiResponse(responseCode = "404", description = "Transaction, compte ou marchand inconnu en amont"),
            @ApiResponse(responseCode = "503", description = "Transaction/account-service injoignable"),
            @ApiResponse(responseCode = "500", description = "Aucune regle activee dans risk_rules")})
    @PostMapping
    public ResponseEntity<AssessmentResponse> evaluate(
            @Valid @RequestBody EvaluationRequest request) {
        AssessmentResponse response = evaluationService.evaluate(request.transactionId());

        // TRACABILITE : TOUTE transaction evaluee ouvre une alerte, MEME si son
        // score est anodin. Sans elle, la transaction n'apparaitrait nulle part
        // cote analyste et serait liquidee hors de tout examen.
        //
        // Cet appel est volontairement hors de la transaction de evaluate() :
        // alert-case-service relit l'evaluation AVANT de creer l'alerte, et
        // l'assessment ne serait pas encore commite a l'interieur.
        alertClient.createAlert(response.assessmentId(), "OTHER");

        URI location = URI.create("/api/evaluations/" + response.assessmentId());
        return ResponseEntity.created(location).body(response);
    }

    /** GET /api/evaluations/{id} */
    @Operation(summary = "Consulter une evaluation", description = "Verdict + facteurs explicatifs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "L'evaluation"),
            @ApiResponse(responseCode = "404", description = "Evaluation inconnue")})
    @GetMapping("/{id}")
    public AssessmentResponse getById(@PathVariable Long id) {
        return evaluationService.getAssessment(id);
    }

    /** GET /api/evaluations/transaction/{transactionId} */
    @Operation(summary = "Dernier verdict d'une transaction",
            description = "L'evaluation la plus recente ; 404 si jamais evaluee.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La derniere evaluation"),
            @ApiResponse(responseCode = "404", description = "Transaction jamais evaluee")})
    @GetMapping("/transaction/{transactionId}")
    public AssessmentResponse getByTransaction(@PathVariable Long transactionId) {
        return evaluationService.getLatestForTransaction(transactionId);
    }

    /** DELETE /api/evaluations?customerId=42 */
    @Operation(summary = "Supprimer les evaluations d'un client",
            description = "A faire APRES la purge des alertes (alert-case) et AVANT celle des "
                    + "transactions ; risk_factors part en CASCADE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre d'evaluations supprimees")})
    @DeleteMapping
    public DeleteSummary deleteByCustomer(@RequestParam Long customerId) {
        return new DeleteSummary(evaluationService.deleteByCustomer(customerId));
    }
}
