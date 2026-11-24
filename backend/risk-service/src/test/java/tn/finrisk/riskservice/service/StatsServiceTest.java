package tn.finrisk.riskservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.finrisk.riskservice.domain.RiskProfile;
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskLevel;
import tn.finrisk.riskservice.repository.RiskAssessmentRepository;
import tn.finrisk.riskservice.repository.RiskProfileRepository;
import tn.finrisk.riskservice.web.dto.EvaluationStatsResponse;

/**
 * Tests PURS des agregats du dashboard evaluations : zero-fill decisions /
 * risques, gestion du vide (AVG null), tri de la watchlist (CRITICAL d'abord,
 * p95 decroissant).
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private RiskAssessmentRepository assessmentRepository;
    @Mock
    private RiskProfileRepository profileRepository;

    @InjectMocks
    private StatsService statsService;

    private static RiskProfile profile(long customerId, RiskLevel level, String p95) {
        RiskProfile profile = new RiskProfile();
        profile.setCustomerId(customerId);
        profile.setProfileRiskLevel(level);
        profile.setP95Amount(new BigDecimal(p95));
        return profile;
    }

    @Test
    void aucuneEvaluation_moyennesZeroEtClePresence() {
        when(assessmentRepository.countGroupByDecision()).thenReturn(List.of());
        when(assessmentRepository.countGroupByRiskLevel()).thenReturn(List.of());
        when(assessmentRepository.avgRiskScore()).thenReturn(null);
        when(assessmentRepository.maxRiskScore()).thenReturn(null);
        when(profileRepository.findAll()).thenReturn(List.of());

        EvaluationStatsResponse stats = statsService.dashboard();

        assertEquals(0, stats.totalAssessments());
        for (Decision decision : Decision.values()) {
            assertTrue(stats.byDecision().containsKey(decision.name()), decision.name());
        }
        for (RiskLevel level : RiskLevel.values()) {
            assertTrue(stats.byRiskLevel().containsKey(level.name()), level.name());
        }
        assertEquals(BigDecimal.ZERO, stats.avgScore());
        assertTrue(stats.watchlist().isEmpty());
    }

    @Test
    void watchlist_TriParNiveauPuisP95() {
        when(assessmentRepository.countGroupByDecision()).thenReturn(List.<Object[]>of(
                new Object[] {Decision.APPROVED, 90L},
                new Object[] {Decision.HOLD, 5L}));
        when(assessmentRepository.countGroupByRiskLevel()).thenReturn(List.<Object[]>of(
                new Object[] {RiskLevel.LOW, 100L}));
        when(assessmentRepository.avgRiskScore()).thenReturn(23.45d);
        when(assessmentRepository.maxRiskScore()).thenReturn(97d);
        // melange volontaire : HIGH, LOW, CRITICAL, HIGH(plus gros p95)
        when(profileRepository.findAll()).thenReturn(List.of(
                profile(1L, RiskLevel.HIGH, "5000"),
                profile(2L, RiskLevel.LOW, "99000"),
                profile(3L, RiskLevel.CRITICAL, "1000"),
                profile(4L, RiskLevel.HIGH, "8000")));

        EvaluationStatsResponse stats = statsService.dashboard();

        assertEquals(95, stats.totalAssessments());
        assertEquals(90L, stats.byDecision().get("APPROVED"));
        assertEquals(5L, stats.byDecision().get("HOLD"));
        assertEquals(100L, stats.byRiskLevel().get("LOW"));
        assertEquals(0L, stats.byRiskLevel().get("CRITICAL")); // zero-fill
        assertEquals(0, new BigDecimal("23.5").compareTo(stats.avgScore()));
        assertEquals(4, stats.watchlist().size()); // 4 profils < taille max 6
        assertEquals(3L, stats.watchlist().getFirst().customerId()); // CRITICAL d'abord
        assertEquals(4L, stats.watchlist().get(1).customerId()); // HIGH p95=8000 avant 5000
        assertEquals(1L, stats.watchlist().get(2).customerId());
        assertEquals(2L, stats.watchlist().get(3).customerId()); // LOW dernier
    }
}
