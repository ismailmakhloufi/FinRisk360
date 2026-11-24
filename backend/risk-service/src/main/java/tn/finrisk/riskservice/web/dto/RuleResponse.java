package tn.finrisk.riskservice.web.dto;

import java.math.BigDecimal;
import tn.finrisk.riskservice.domain.enums.RiskCategory;
import tn.finrisk.riskservice.domain.enums.Severity;

/** Une regle du catalogue telle que visible/modifiable via l'API. */
public record RuleResponse(
        Long ruleId,
        String ruleCode,
        String ruleName,
        RiskCategory riskCategory,
        String comparisonOperator,
        BigDecimal thresholdValue,
        BigDecimal weight,
        Severity severity,
        Boolean enabled) {
}
