package tn.finrisk.alertcaseservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * Tests PURS des agregats du dashboard : zero-fill (tous les statuts et les
 * 7 jours presents, meme sans donnee), cumuls de groupes, absorbes.
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private AlertRepository alertRepository;
    @Mock
    private CaseRepository caseRepository;
    @Mock
    private AlertGroupRepository groupRepository;

    @InjectMocks
    private StatsService statsService;

    @Test
    void aucuneDonnee_ToutEstZeroMaisPresent() {
        when(alertRepository.countGroupByStatus()).thenReturn(List.<Object[]>of());
        when(alertRepository.countGroupBySeverity()).thenReturn(List.<Object[]>of());
        when(caseRepository.countGroupByStatus()).thenReturn(List.<Object[]>of());
        when(groupRepository.findByStatusOrderByUpdatedAtDesc(GroupStatus.OPEN)).thenReturn(List.of());
        when(alertRepository.sumAbsorbedAmount()).thenReturn(null);
        when(alertRepository.countByDaySince(any())).thenReturn(List.of());
        when(alertRepository.countActiveBySeverity(any(), any())).thenReturn(0L);
        when(alertRepository.countByStatusIn(any())).thenReturn(0L);
        when(alertRepository.avgResolutionMinutes()).thenReturn(null);

        AlertStatsResponse stats = statsService.dashboard();

        assertEquals(0, stats.totalAlerts());
        // zero-fill : toutes les cles sont la (le donut reste stable)
        for (AlertStatus status : AlertStatus.values()) {
            assertTrue(stats.byStatus().containsKey(status.name()), status.name());
        }
        for (Severity severity : Severity.values()) {
            assertTrue(stats.bySeverity().containsKey(severity.name()), severity.name());
        }
        assertEquals(7, stats.activity7d().size());
        assertEquals(BigDecimal.ZERO, stats.absorbedAmount());
        assertEquals(BigDecimal.ZERO, stats.groupCumulAmount());
        assertEquals(0, stats.activeHighCritical());
        assertEquals(0, stats.closedAlerts());
        assertEquals(null, stats.avgResolutionMinutes());
    }

    @Test
    void agregats_MergesSansEcraser() {
        when(alertRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[] {AlertStatus.OPEN, 5L},
                new Object[] {AlertStatus.ABSORBED, 8L}));
        when(alertRepository.countGroupBySeverity()).thenReturn(List.<Object[]>of(
                new Object[] {Severity.HIGH, 12L}));
        when(caseRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[] {CaseStatus.OPEN, 2L},
                new Object[] {CaseStatus.UNDER_REVIEW, 1L},
                new Object[] {CaseStatus.CLOSED, 7L}));
        AlertGroup group = new AlertGroup();
        group.setTotalAmount(new BigDecimal("7500"));
        when(groupRepository.findByStatusOrderByUpdatedAtDesc(GroupStatus.OPEN))
                .thenReturn(List.of(group));
        when(alertRepository.sumAbsorbedAmount()).thenReturn(new BigDecimal("1200.50"));
        // une seule journee peuplee : les 6 autres restent a 0
        when(alertRepository.countByDaySince(any())).thenReturn(List.<Object[]>of(
                new Object[] {LocalDate.now(ZoneOffset.UTC).toString(), 4L}));
        when(alertRepository.countActiveBySeverity(any(), any())).thenReturn(7L);
        when(alertRepository.countByStatusIn(any())).thenReturn(4L);
        when(alertRepository.avgResolutionMinutes()).thenReturn(25.5);

        AlertStatsResponse stats = statsService.dashboard();

        assertEquals(13, stats.totalAlerts());
        assertEquals(5L, stats.byStatus().get("OPEN"));
        assertEquals(8L, stats.byStatus().get("ABSORBED"));
        assertEquals(0L, stats.byStatus().get("RESOLVED"));
        assertEquals(12L, stats.bySeverity().get("HIGH"));
        assertEquals(10, stats.totalCases());
        assertEquals(3, stats.openCases()); // OPEN + UNDER_REVIEW (CLOSED exclu)
        assertEquals(1, stats.openGroups());
        assertEquals(new BigDecimal("7500"), stats.groupCumulAmount());
        assertEquals(new BigDecimal("1200.50"), stats.absorbedAmount());
        assertEquals(7, stats.activity7d().size());
        long total7j = stats.activity7d().stream().mapToLong(AlertStatsResponse.DayCount::count).sum();
        assertEquals(4, total7j);
        assertEquals(7, stats.activeHighCritical());
        assertEquals(4, stats.closedAlerts());
        assertEquals(25.5, stats.avgResolutionMinutes());
    }

    @Test
    void filtresBanniere_SeulesLesActivesComptees() {
        // Le service ne transmet que les bonnes listes d'enum : la banniere
        // ne doit compter que HIGH/CRITICAL encore non terminales.
        when(alertRepository.countGroupByStatus()).thenReturn(List.<Object[]>of());
        when(alertRepository.countGroupBySeverity()).thenReturn(List.<Object[]>of());
        when(caseRepository.countGroupByStatus()).thenReturn(List.<Object[]>of());
        when(groupRepository.findByStatusOrderByUpdatedAtDesc(GroupStatus.OPEN)).thenReturn(List.of());
        when(alertRepository.sumAbsorbedAmount()).thenReturn(null);
        when(alertRepository.countByDaySince(any())).thenReturn(List.of());
        when(alertRepository.countActiveBySeverity(
                org.mockito.ArgumentMatchers.eq(List.of(Severity.HIGH, Severity.CRITICAL)),
                org.mockito.ArgumentMatchers.eq(List.of(AlertStatus.RESOLVED,
                        AlertStatus.FALSE_POSITIVE, AlertStatus.ABSORBED))))
                .thenReturn(3L);
        when(alertRepository.countByStatusIn(
                org.mockito.ArgumentMatchers.eq(List.of(AlertStatus.RESOLVED,
                        AlertStatus.FALSE_POSITIVE)))).thenReturn(2L);
        when(alertRepository.avgResolutionMinutes()).thenReturn(null);

        AlertStatsResponse stats = statsService.dashboard();

        assertEquals(3, stats.activeHighCritical());
        assertEquals(2, stats.closedAlerts());
        assertEquals(null, stats.avgResolutionMinutes());
    }
}
