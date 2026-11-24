package tn.finrisk.alertcaseservice.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * KPI du dashboard SENTINEL — GET /api/alerts/stats.
 *
 * Tout est agrege COTE BASE (GROUP BY) : les anciens compteurs faisaient un
 * filtre JavaScript sur une page de 50 lignes, faux des qu'il y a plus de
 * 50 alertes. Les jours sont en UTC (matches SQL AT TIME ZONE 'UTC').
 */
public record AlertStatsResponse(
        long totalAlerts,
        Map<String, Long> byStatus,
        Map<String, Long> bySeverity,
        long totalCases,
        long openCases,
        long openGroups,
        BigDecimal groupCumulAmount,
        BigDecimal absorbedAmount,
        long activeHighCritical,
        long closedAlerts,
        Double avgResolutionMinutes,
        List<DayCount> activity7d) {

    public record DayCount(String day, long count) {
    }
}
