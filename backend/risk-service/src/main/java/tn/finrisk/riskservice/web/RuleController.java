package tn.finrisk.riskservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.riskservice.service.EvaluationService;
import tn.finrisk.riskservice.web.dto.RuleResponse;
import tn.finrisk.riskservice.web.dto.RuleUpdateRequest;

/**
 * Catalogue des regles : consultables et modifiables SANS redemarrage.
 * Demo de soutenance : basculer enabled=false ou changer un poids,
 * re-evaluer, observer le score bouger.
 */
@Tag(name = "Rules", description = "Catalogue des regles de risque (configurables a chaud)")
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final EvaluationService evaluationService;

    public RuleController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** GET /api/rules */
    @Operation(summary = "Lister les regles",
            description = "Tout le catalogue, actives ou desactivees, ordre RULE-001..N.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Le catalogue"))
    @GetMapping
    public List<RuleResponse> getAll() {
        return evaluationService.listRules();
    }

    /** PUT /api/rules/{id} */
    @Operation(summary = "Modifier une regle",
            description = "Mise a jour partielle : seuls les champs fournis sont appliques "
                    + "(enabled, weight 0..1, thresholdValue).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Regle mise a jour"),
            @ApiResponse(responseCode = "400", description = "Poids hors bornes [0;1]"),
            @ApiResponse(responseCode = "404", description = "Regle inconnue")})
    @PutMapping("/{id}")
    public RuleResponse update(@PathVariable Long id,
                               @Valid @RequestBody RuleUpdateRequest request) {
        return evaluationService.updateRule(id, request);
    }
}
