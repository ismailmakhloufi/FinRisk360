package tn.finrisk.riskservice.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * KPI du dashboard SENTINEL — GET /api/evaluations/stats.
 *
 * Agrégats cote base (GROUP BY sur risk_assessments) + la watchlist des
 * clients dont le profil de risque (baseline 90 j, risk_profiles) est le
 * plus eleve — donnee JAMAIS exploitee avant ce panneau.
 */
public record EvaluationStatsResponse(
        long totalAssessments,
        Map<String, Long> byDecision,
        Map<String, Long> byRiskLevel,
        BigDecimal avgScore,
        BigDecimal maxScore,
        List<ProfileDigest> watchlist) {

    /** Digest d'un profil de risque (baseline 90 jours du client). */
    public record ProfileDigest(Long customerId, String profileRiskLevel,
            BigDecimal avgAmount, BigDecimal p95Amount, String usualCountry,
            Integer usualTransactionHour, int statsWindowDays) {
    }
}
