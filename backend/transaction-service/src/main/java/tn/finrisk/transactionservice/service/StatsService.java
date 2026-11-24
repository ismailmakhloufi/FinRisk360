package tn.finrisk.transactionservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.repository.TransactionRepository;
import tn.finrisk.transactionservice.web.dto.TransactionStatsResponse;

/**
 * KPI du dashboard SENTINEL (GET /api/transactions/stats) :
 * top beneficiaires, activite sur N jours (7 ou 30, parametre "days"),
 * volume + tendance vs la fenetre precedente, repartition par type/canal.
 * Lecture seule, aucune dependance aux autres services.
 */
@Service
public class StatsService {

    /** Nombre de beneficiaires renvoyes au classement. */
    static final int TOP_SIZE = 8;
    static final int DEFAULT_DAYS = 7;
    static final int MAX_DAYS = 90;

    private final TransactionRepository transactionRepository;

    public StatsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public TransactionStatsResponse dashboard(int days) {
        int window = days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);

        List<TransactionStatsResponse.TopBeneficiary> top = new ArrayList<>();
        for (Object[] row : transactionRepository.topBeneficiaries(PageRequest.of(0, TOP_SIZE))) {
            String name = String.valueOf(row[0]);
            top.add(new TransactionStatsResponse.TopBeneficiary(name,
                    ((Number) row[1]).longValue(), (BigDecimal) row[2]));
        }

        List<TransactionStatsResponse.DayCount> activity = new ArrayList<>();
        Map<String, Long> byDay = new LinkedHashMap<>();
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(window - 1);
        LocalDate cursor = start;
        for (int i = 0; i < window; i++) {
            byDay.put(cursor.toString(), 0L);
            cursor = cursor.plusDays(1);
        }
        OffsetDateTime since = start.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        BigDecimal volume = BigDecimal.ZERO;
        for (Object[] row : transactionRepository.statsByDaySince(since)) {
            String day = String.valueOf(row[0]);
            byDay.put(day, ((Number) row[1]).longValue());
            BigDecimal dayVolume = (BigDecimal) row[2];
            volume = volume.add(dayVolume);
        }
        byDay.forEach((day, count) -> activity.add(
                new TransactionStatsResponse.DayCount(day, count)));

        // Tendance : meme fenetre juste avant (J-2N .. J-N). Division par zero
        // (periode precedente vide) => null, le front affiche "—".
        OffsetDateTime prevFrom = start.minusDays(window)
                .atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        BigDecimal prevVolume = transactionRepository.sumBetween(prevFrom, since);
        Double trendPct = null;
        if (prevVolume != null && prevVolume.signum() > 0) {
            trendPct = volume.subtract(prevVolume)
                    .divide(prevVolume, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        Map<String, Long> byType = zeroFill(TransactionType.values());
        for (Object[] row : transactionRepository.countGroupByTransactionType()) {
            byType.put(key(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> byChannel = zeroFill(TransactionChannel.values());
        for (Object[] row : transactionRepository.countGroupByChannel()) {
            byChannel.put(key(row[0]), ((Number) row[1]).longValue());
        }

        return new TransactionStatsResponse(transactionRepository.count(), top, activity,
                volume, trendPct, byType, byChannel);
    }

    /** Tous les enumes sont presents (a 0 s'ils en manquent) : le graphe est stable. */
    private static Map<String, Long> zeroFill(Enum<?>[] values) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            map.put(value.name(), 0L);
        }
        return map;
    }

    /** channel est nullable en base : NULL -> « AUTRE » (jamais « null » a l'ecran). */
    private static String key(Object value) {
        return value == null ? "AUTRE" : String.valueOf(value);
    }
}
