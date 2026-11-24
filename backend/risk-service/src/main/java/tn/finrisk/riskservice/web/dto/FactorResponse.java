package tn.finrisk.riskservice.web.dto;

import java.math.BigDecimal;

/**
 * Un facteur de risk_factors : POURQUOI le score est ce qu'il est.
 * factorScore = 0.00 avec une raison "Regle neutralisee..." = la regle n'a pas
 * pu jouer (pas d'antecedent...) : c'est visible, pas silencieux.
 */
public record FactorResponse(
        Long ruleId,
        String ruleCode,
        String ruleName,
        String factorType,
        BigDecimal factorScore,
        BigDecimal weight,
        String reason) {
}
