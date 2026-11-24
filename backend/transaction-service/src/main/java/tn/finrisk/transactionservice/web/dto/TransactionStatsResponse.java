package tn.finrisk.transactionservice.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * KPI du dashboard SENTINEL — GET /api/transactions/stats?days=N.
 * Agregats cote base (GROUP BY) : jamais de comptage sur pages paginees.
 * "activity" couvre les N derniers jours (defaut 7, max 90) ; "trendPct"
 * compare le volume de cette fenetre a la precedente (null si base vide).
 */
public record TransactionStatsResponse(
        long totalTransactions,
        List<TopBeneficiary> topBeneficiaries,
        List<DayCount> activity,
        BigDecimal volume,
        Double trendPct,
        Map<String, Long> byType,
        Map<String, Long> byChannel) {

    public record TopBeneficiary(String name, long count, BigDecimal totalAmount) {
    }

    public record DayCount(String day, long count) {
    }
}
