package tn.finrisk.aiservice.client.dto;

import java.math.BigDecimal;

/**
 * Un facteur de risk_factors lu CHEZ risk-service : POURQUOI le score est
 * ce qu'il est. On ne partage JAMAIS les enums d'un autre service (règle M0) :
 * factorType arrive en String.
 */
public record FactorView(
        Long ruleId,
        String ruleCode,
        String ruleName,
        String factorType,
        BigDecimal factorScore,
        BigDecimal weight,
        String reason) {
}
