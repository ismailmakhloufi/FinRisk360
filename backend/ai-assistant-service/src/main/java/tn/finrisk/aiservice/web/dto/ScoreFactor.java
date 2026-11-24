package tn.finrisk.aiservice.web.dto;

import java.math.BigDecimal;

/**
 * Un facteur du SCORE MOTEUR projeté pour la constellation / le chat :
 * POURQUOI le score risk-service est ce qu'il est, avec ses POINTS et son
 * POIDS. Source : FactorView lu chez risk-service (règle M0 : pas d'enum
 * partagé, tout arrive en String / BigDecimal).
 */
public record ScoreFactor(
        String ruleCode,
        String ruleName,
        BigDecimal points,
        BigDecimal weight,
        String reason) {
}
