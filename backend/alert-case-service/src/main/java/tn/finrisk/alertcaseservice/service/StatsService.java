package tn.finrisk.alertcaseservice.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.domain.AlertGroup;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;
import tn.finrisk.alertcaseservice.domain.enums.GroupStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertGroupRepository;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.repository.CaseRepository;
import tn.finrisk.alertcaseservice.web.dto.AlertStatsResponse;

/**
 * KPI du dashboard SENTINEL (GET /api/alerts/stats).
 *
 * Requetes d'agregation cote base (GROUP BY) — jamais de comptage sur une
 * page de 50 lignes. Lecture seule, sans appel aux services voisins : le
 * dashboard s'affiche meme si transaction/risk sont down.
 */
@Service
public class StatsService {

    private final AlertRepository alertRepository;
    private final CaseRepository caseRepository;
    private final AlertGroupRepository groupRepository;

    public StatsService(AlertRepository alertRepository, CaseRepository caseRepository,
            AlertGroupRepository groupRepository) {
        this.alertRepository = alertRepository;
        this.caseRepository = caseRepository;
        this.groupRepository = groupRepository;
    }

    @Transactional(readOnly = true)
    public AlertStatsResponse dashboard() {
        Map<String, Long> byStatus = zeroFillStatus();
        for (Object[] row : alertRepository.countGroupByStatus()) {
            byStatus.put(String.valueOf(row[0]), (Long) row[1]);
        }

        Map<String, Long> bySeverity = zeroFillSeverity();
        for (Object[] row : alertRepository.countGroupBySeverity()) {
            bySeverity.put(String.valueOf(row[0]), (Long) row[1]);
        }

        long totalAlerts = byStatus.values().stream().mapToLong(Long::longValue).sum();

        long totalCases = 0;
        long openCases = 0;
        for (Object[] row : caseRepository.countGroupByStatus()) {
            long count = (Long) row[1];
            totalCases += count;
            CaseStatus status = (CaseStatus) row[0];
            if (status == CaseStatus.OPEN || status == CaseStatus.UNDER_REVIEW) {
                openCases += count;
            }
        }

        long openGroups = 0;
        BigDecimal groupCumul = BigDecimal.ZERO;
        for (AlertGroup group : groupRepository.findByStatusOrderByUpdatedAtDesc(GroupStatus.OPEN)) {
            openGroups++;
            groupCumul = groupCumul.add(group.getTotalAmount());
        }

        BigDecimal absorbed = alertRepository.sumAbsorbedAmount();

        long activeHighCritical = alertRepository.countActiveBySeverity(
                List.of(Severity.HIGH, Severity.CRITICAL),
                List.of(AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE, AlertStatus.ABSORBED));
        long closedAlerts = alertRepository.countByStatusIn(
                List.of(AlertStatus.RESOLVED, AlertStatus.FALSE_POSITIVE));
        Double avgResolutionMinutes = alertRepository.avgResolutionMinutes();

        return new AlertStatsResponse(totalAlerts, byStatus, bySeverity,
                totalCases, openCases, openGroups, groupCumul,
                absorbed == null ? BigDecimal.ZERO : absorbed,
                activeHighCritical, closedAlerts, avgResolutionMinutes,
                activity7d());
    }

    /**
     * Activite des 7 derniers jours (jours UTC), AVEC zero-fill : une journee
     * sans alerte reste visible sur la sparkline (un trou serait pris pour un
     * bug de collecte).
     */
    private List<AlertStatsResponse.DayCount> activity7d() {
        // 7 jours UTC : de J-6 (debut) a J (aujourd'hui inclus).
        java.time.LocalDate today = java.time.LocalDate.now(ZoneOffset.UTC);
        java.time.LocalDate start = today.minusDays(6);
        OffsetDateTime since = start.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        Map<String, Long> byDay = new LinkedHashMap<>();
        java.time.LocalDate cursor = start;
        for (int i = 0; i < 7; i++) {
            byDay.put(cursor.toString(), 0L);
            cursor = cursor.plusDays(1);
        }
        for (Object[] row : alertRepository.countByDaySince(since)) {
            byDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<AlertStatsResponse.DayCount> result = new ArrayList<>();
        byDay.forEach((day, count) -> result.add(new AlertStatsResponse.DayCount(day, count)));
        return result;
    }

    /** Tous les statuts sont presents (a 0 s'il en manque) : le donut est stable. */
    static Map<String, Long> zeroFillStatus() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (AlertStatus status : AlertStatus.values()) {
            map.put(status.name(), 0L);
        }
        return map;
    }

    static Map<String, Long> zeroFillSeverity() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            map.put(severity.name(), 0L);
        }
        return map;
    }
}
