package tn.finrisk.alertcaseservice.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Vue reduite de l'evaluation lue CHEZ risk-service (GET /api/evaluations/{id}).
 * Record Jackson : les enums distantes (riskLevel, decision) arrivent en JSON
 * et se rangent en String — on ne partage JAMAIS les enums d'un autre service
 * (chaque service possede les siennes, regle M0).
 *
 * Champs dont alert-case a besoin : le score/verdict pour qualifier l'alerte
 * (severite par defaut) et assessedAt pour situer l'alerte dans le temps.
 */
public record EvaluationView(
        Long assessmentId,
        Long transactionId,
        BigDecimal riskScore,
        String riskLevel,
        String decision,
        OffsetDateTime assessedAt,
        String engineVersion) {
}
