package tn.finrisk.riskservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.riskservice.domain.RiskProfile;
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskLevel;
import tn.finrisk.riskservice.repository.RiskAssessmentRepository;
import tn.finrisk.riskservice.repository.RiskProfileRepository;
import tn.finrisk.riskservice.web.dto.EvaluationStatsResponse;

/**
 * KPI du dashboard SENTINEL (GET /api/evaluations/stats) :
 * decisions / niveaux de risque (GROUP BY base), score moyen et max, et la
 * WATCHLIST des profils de risque les plus eleves (baseline 90 j) —
 * risk_profiles etait recalcule a chaque evaluation mais jamais affiche nulle part.
 */
@Service
public class StatsService {

    /** Profils renvoyes dans la watchlist. */
    static final int WATCHLIST_SIZE = 6;

    private final RiskAssessmentRepository assessmentRepository;
    private final RiskProfileRepository profileRepository;

    public StatsService(RiskAssessmentRepository assessmentRepository,
            RiskProfileRepository profileRepository) {
        this.assessmentRepository = assessmentRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public EvaluationStatsResponse dashboard() {
        Map<String, Long> byDecision = new LinkedHashMap<>();
        for (Decision decision : Decision.values()) {
            byDecision.put(decision.name(), 0L);
        }
        long total = 0;
        for (Object[] row : assessmentRepository.countGroupByDecision()) {
            long count = (Long) row[1];
            byDecision.put(String.valueOf(row[0]), count);
            total += count;
        }

        Map<String, Long> byRiskLevel = new LinkedHashMap<>();
        for (RiskLevel level : RiskLevel.values()) {
            byRiskLevel.put(level.name(), 0L);
        }
        for (Object[] row : assessmentRepository.countGroupByRiskLevel()) {
            byRiskLevel.put(String.valueOf(row[0]), (Long) row[1]);
        }

        BigDecimal avg = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        Number avgRaw = assessmentRepository.avgRiskScore();
        Number maxRaw = assessmentRepository.maxRiskScore();
        if (avgRaw != null) {
            avg = BigDecimal.valueOf(avgRaw.doubleValue()).setScale(1, RoundingMode.HALF_UP);
            max = BigDecimal.valueOf(maxRaw.doubleValue()).setScale(1, RoundingMode.HALF_UP);
        }

        return new EvaluationStatsResponse(total, byDecision, byRiskLevel, avg, max,
                watchlist());
    }

    /**
     * Les profils aux niveaux de risque les plus hauts, p95 le plus grand
     * en cas d'egalite. Tries en Java : le nombre de profils = le nombre de
     * clients (petit volume), et l'ordinal de RiskLevel est le bon critere.
     */
    private List<EvaluationStatsResponse.ProfileDigest> watchlist() {
        List<RiskProfile> profiles = new ArrayList<>(profileRepository.findAll());
        profiles.sort(Comparator
                .comparingInt((RiskProfile p) -> p.getProfileRiskLevel() == null
                        ? RiskLevel.LOW.ordinal() : p.getProfileRiskLevel().ordinal())
                .reversed()
                .thenComparing(p -> p.getP95Amount() == null ? BigDecimal.ZERO : p.getP95Amount(),
                        Comparator.reverseOrder()));
        List<EvaluationStatsResponse.ProfileDigest> result = new ArrayList<>();
        for (RiskProfile profile : profiles) {
            if (result.size() >= WATCHLIST_SIZE) {
                break;
            }
            result.add(new EvaluationStatsResponse.ProfileDigest(
                    profile.getCustomerId(),
                    profile.getProfileRiskLevel() == null ? null
                            : profile.getProfileRiskLevel().name(),
                    profile.getAvgTransactionAmount(),
                    profile.getP95Amount(),
                    profile.getUsualCountry(),
                    profile.getUsualTransactionHour(),
                    profile.getStatsWindowDays()));
        }
        return result;
    }
}
