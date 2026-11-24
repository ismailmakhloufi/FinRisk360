package tn.finrisk.aiservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.aiservice.service.AiAnalysisService;
import tn.finrisk.aiservice.service.ConstellationService;
import tn.finrisk.aiservice.web.dto.AnalysisResponse;
import tn.finrisk.aiservice.web.dto.ConstellationResponse;

/**
 * Façade de l'assistant IA (M6) : l'analyste (ou l'UI) demande une analyse
 * QUAND il ouvre une alerte — pas de déclenchement automatique à la création
 * (alert-case-service n'est donc jamais couplé à l'IA).
 */
@Tag(name = "Assistant IA", description = "M6 : analyse d'alerte, avis et explication (à côté du score)")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAnalysisService analysisService;
    private final ConstellationService constellationService;

    public AiController(AiAnalysisService analysisService,
            ConstellationService constellationService) {
        this.analysisService = analysisService;
        this.constellationService = constellationService;
    }

    /** POST /api/ai/alerts/{alertId}/analyze */
    @Operation(summary = "Analyser une alerte",
            description = "Chaîne de lecture : alerte -> evaluation -> transaction -> compte -> "
                    + "customer -> historique 90j -> scan global. Rend score moteur + avis IA "
                    + "(verdict, confiance, insights avec preuves) + explication. Jamais de "
                    + "décision : l'analyste tranche.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analyse rendue (score + avis côte à côte)"),
            @ApiResponse(responseCode = "404", description = "Alerte ou ressource distante inconnue"),
            @ApiResponse(responseCode = "503", description = "Un des services lus est injoignable")})
    @PostMapping("/alerts/{alertId}/analyze")
    public AnalysisResponse analyze(@PathVariable Long alertId) {
        return analysisService.analyze(alertId);
    }

    /** POST /api/ai/alerts/{alertId}/constellation */
    @Operation(summary = "Construire la constellation d'investigation",
            description = "Graphe déterministe d'entités réelles (clients, comptes, "
                    + "transactions, bénéficiaires, devices, IP, pays) avec relations "
                    + "vérifiables et preuves, + narratif français (LLM Ollama ou repli "
                    + "déterministe). Le graphe n'est JAMAIS inventé : chaque nœud naît "
                    + "d'une donnée API. Aucune décision : avis pour l'analyste.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Constellation rendue (graphe + narratif)"),
            @ApiResponse(responseCode = "404", description = "Alerte ou ressource distante inconnue"),
            @ApiResponse(responseCode = "503", description = "Un des services lus est injoignable")})
    @PostMapping("/alerts/{alertId}/constellation")
    public ConstellationResponse constellation(@PathVariable Long alertId) {
        return constellationService.constellation(alertId);
    }
}
