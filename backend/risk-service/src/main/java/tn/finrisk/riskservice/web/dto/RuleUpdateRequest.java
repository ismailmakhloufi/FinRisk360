package tn.finrisk.riskservice.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Mise a jour partielle d'une regle (PUT /api/rules/{id}) :
 * seuls les champs non nuls sont appliques. Poids borne 0..1 pour preserver
 * l'invariant "somme des poids = 1.00" du catalogue.
 */
public record RuleUpdateRequest(
        Boolean enabled,
        @DecimalMin(value = "0", message = "weight doit etre >= 0")
        @DecimalMax(value = "1", message = "weight doit etre <= 1")
        BigDecimal weight,
        BigDecimal thresholdValue) {
}
