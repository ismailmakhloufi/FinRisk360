package tn.finrisk.alertcaseservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.service.NightAgentService;
import tn.finrisk.alertcaseservice.web.dto.NightAgentResponse;

/**
 * AGENT NOCTURNE : rapport + declencheur.
 *
 *   GET  /api/night-agent/decisions — le rapport du matin (ce que l'agent
 *        a valide la nuit, ce qu'il a laisse, et POURQUOI)
 *   GET  /api/night-agent/status    — configuration + derniere execution
 *   POST /api/night-agent/run       — declencheur MANUEL (demo / rattrapage) :
 *        respecte l'activation et la fenetre horaire configurees
 *
 * Aucune ecriture ici : l'agent ne fait que valider (jamais annuler) —
 * cf. NightAgentDecisionRule pour la matrice.
 */
@Tag(name = "Agent nocturne",
        description = "Balayage nocturne de la file EN ATTENTE : matrice hybride moteur + IA")
@RestController
@RequestMapping("/api/night-agent")
public class NightAgentController {

    private final NightAgentService nightAgentService;

    public NightAgentController(NightAgentService nightAgentService) {
        this.nightAgentService = nightAgentService;
    }

    /** GET /api/night-agent/decisions */
    @Operation(summary = "Rapport de l'agent nocturne",
            description = "Decisions prises, derniere execution d'abord. "
                    + "agentAction = VALIDATED (validee la nuit) ou LEFT_PENDING (laissee)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page du rapport"),
            @ApiResponse(responseCode = "400", description = "Parametre invalide")})
    @GetMapping("/decisions")
    public Page<NightAgentResponse> decisions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return nightAgentService.report(PageRequest.of(page, Math.min(size, 100)))
                .map(NightAgentResponse::from);
    }

    /** GET /api/night-agent/status */
    @Operation(summary = "Configuration et derniere execution de l'agent")
    @ApiResponse(responseCode = "200", description = "Etat de l'agent")
    @GetMapping("/status")
    public NightAgentService.Status status() {
        return nightAgentService.status();
    }

    /** POST /api/night-agent/run */
    @Operation(summary = "Declencher un balayage maintenant",
            description = "Autorise uniquement lorsque l'agent est actif et dans sa fenetre horaire.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Synthese du balayage"),
            @ApiResponse(responseCode = "409", description = "Agent desactive ou hors fenetre"),
            @ApiResponse(responseCode = "503", description = "Service distant injoignable")})
    @PostMapping("/run")
    public NightAgentService.RunSummary run() {
        return nightAgentService.runManual();
    }
}
