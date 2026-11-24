package tn.finrisk.aiservice.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Evaluation lue CHEZ risk-service (GET /api/evaluations/{id}) : le score,
 * sa décision ET les facteurs qui l'expliquent — tout ce qu'il faut à
 * Explain pour reprendre le raisonnement du moteur.
 *
 * Enums distantes (riskLevel, decision) en String (règle M0).
 */
public record EvaluationView(
        Long assessmentId,
        Long transactionId,
        BigDecimal riskScore,
        String riskLevel,
        String decision,
        OffsetDateTime assessedAt,
        String engineVersion,
        List<FactorView> factors) {

    /** Les facteurs peuvent arriver nuls (JSON minimal) : jamais de NPE. */
    public List<FactorView> factorsOrEmpty() {
        return factors == null ? List.of() : factors;
    }
}
