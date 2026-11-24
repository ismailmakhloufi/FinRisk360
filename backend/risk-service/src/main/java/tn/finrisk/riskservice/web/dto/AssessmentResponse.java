package tn.finrisk.riskservice.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskLevel;

/** Verdict complet rendu par le moteur : le chiffre ET son explication. */
public record AssessmentResponse(
        Long assessmentId,
        Long transactionId,
        BigDecimal riskScore,
        RiskLevel riskLevel,
        Decision decision,
        OffsetDateTime assessedAt,
        String engineVersion,
        List<FactorResponse> factors) {
}
