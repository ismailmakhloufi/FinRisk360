package tn.finrisk.riskservice.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tn.finrisk.riskservice.client.dto.TransactionView;

/**
 * ① FEATURE ENGINE : transforme (transaction + historique du client) en features
 * exploitables par les regles. PUR (aucun acces reseau ni base) : testable seul.
 *
 * Choix structurants :
 *  - historique au niveau CLIENT (tous les comptes) => velocite et baseline justes ;
 *  - fenetres glissantes ancrees sur transactionDate (pas sur le jour calendaire :
 *    sinon contournement a minuit) ;
 *  - heures converties en risk.timezone (l'heure UTC decale d'1h la regle 00h-05h) ;
 *  - ecart-type (sigma) calcule a la volee : la table risk_profiles ne le stocke pas.
 */
@Component
public class FeatureEngine {

    private final ZoneId riskZone;
    private final int statsWindowDays;
    private final BigDecimal smallAmountMax;

    public FeatureEngine(@Value("${risk.timezone:Africa/Tunis}") String timezone,
                         @Value("${risk.stats-window-days:90}") int statsWindowDays,
                         @Value("${risk.structuring.small-amount-max:5000}") BigDecimal smallAmountMax) {
        this.riskZone = ZoneId.of(timezone);
        this.statsWindowDays = statsWindowDays;
        this.smallAmountMax = smallAmountMax;
    }

    /**
     * @param transaction   la transaction evaluee
     * @param customerId    client proprietaire (via account-service)
     * @param history       TOUTES les transactions du client (tous comptes, fenetre 90j,
     *                      triee ou non, INCLUANT la transaction evaluee)
     * @param merchantAgeDays age du marchand en jours (null si pas de marchand)
     */
    public TransactionFeatures compute(TransactionView transaction, Long customerId,
                                       List<TransactionView> history,
                                       Integer merchantAgeDays) {
        List<TransactionView> sorted = history.stream()
                .filter(t -> t.transactionDate() != null)
                .sorted(Comparator.comparing(TransactionView::transactionDate))
                .toList();

        List<TransactionView> beforeSelf = sorted.stream()
                .filter(t -> !Objects.equals(t.transactionId(), transaction.transactionId()))
                .toList();

        TransactionFeatures.HistoryStats stats = beforeSelf.isEmpty()
                ? null : historyStats(beforeSelf);

        int localHour = transaction.transactionDate().atZoneSameInstant(riskZone).getHour();
        boolean tnd = transaction.currency() != null
                && "TND".equalsIgnoreCase(transaction.currency());

        return new TransactionFeatures(
                transaction,
                customerId,
                stats,
                activityStats(transaction, sorted),
                merchantAgeDays,
                localHour,
                tnd);
    }

    /** Baseline 90j (hors transaction evaluee). */
    private TransactionFeatures.HistoryStats historyStats(List<TransactionView> sorted) {
        List<BigDecimal> amounts = sorted.stream().map(TransactionView::amount).toList();
        List<BigDecimal> sortedAmounts = amounts.stream().sorted().toList();
        int n = sortedAmounts.size();

        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // Ecart-type population : sqrt( somme (x - moyenne)^2 / n ).
        // En double : precision largement suffisante pour un score a 2 decimales.
        double variance = amounts.stream()
                .mapToDouble(a -> Math.pow(a.doubleValue() - avg.doubleValue(), 2))
                .sum() / n;
        BigDecimal sigma = BigDecimal.valueOf(Math.sqrt(variance)).setScale(2, RoundingMode.HALF_UP);

        return new TransactionFeatures.HistoryStats(
                n,
                avg,
                sigma,
                percentile(sortedAmounts, 50),
                sortedAmounts.getLast(),
                percentile(sortedAmounts, 95),
                percentile(sortedAmounts, 99),
                BigDecimal.valueOf(n)
                        .divide(BigDecimal.valueOf(statsWindowDays), 2, RoundingMode.HALF_UP),
                modeCountry(sorted),
                modeLocalHour(sorted),
                (int) sorted.stream()
                        .map(TransactionView::merchantId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count());
    }

    /**
     * Activite recente (fenetres glissantes) et couples suspects.
     *
     * @param self la transaction evaluee — elle compte dans sa propre fenetre
     *             24h : c'est elle qui ACHEVE le motif, et c'est elle qu'on note.
     */
    private TransactionFeatures.ActivityStats activityStats(TransactionView self,
                                                            List<TransactionView> sorted) {
        OffsetDateTime ref = self.transactionDate();
        OffsetDateTime from24 = ref.minusHours(24);

        List<TransactionView> last24 = sorted.stream()
                .filter(t -> !t.transactionDate().isBefore(from24)
                        && !t.transactionDate().isAfter(ref))
                .toList();

        int txCount24h = last24.size();
        BigDecimal cumul24hTnd = last24.stream()
                .filter(t -> t.currency() != null && "TND".equalsIgnoreCase(t.currency()))
                .map(TransactionView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int failedCount24h = (int) last24.stream()
                .filter(t -> "FAILED".equals(t.status()))
                .count();

        // Structuration : transactions SORTANTES (pas les depots, l'argent ne
        // quitte pas la banque) de montant <= le plafond « petit montant ».
        // Le plafond reflete le seuil de declaration de RULE-001 : en dessous,
        // AUCUNE regle de montant ne se declenche, ce qui est precisement ce
        // qu'exploite un fraudeur qui fractionne.
        List<TransactionView> smallOut = last24.stream()
                .filter(t -> t.currency() != null && "TND".equalsIgnoreCase(t.currency()))
                .filter(t -> isOutgoing(t.transactionType()))
                .filter(t -> t.amount() != null
                        && t.amount().compareTo(smallAmountMax) <= 0)
                .toList();
        int smallTxCount24h = smallOut.size();
        BigDecimal smallTxSum24h = smallOut.stream()
                .map(TransactionView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Couple pays different : l'ecart minimal se trouve toujours entre deux
        // transactions ADJACENTES triees (toute paire non adjacente est plus eloignee).
        Integer minGapCountry = null;
        for (int i = 1; i < sorted.size(); i++) {
            TransactionView prev = sorted.get(i - 1);
            TransactionView cur = sorted.get(i);
            if (prev.country() != null && cur.country() != null
                    && !prev.country().equals(cur.country())) {
                int gap = (int) ChronoUnit.MINUTES.between(prev.transactionDate(), cur.transactionDate());
                minGapCountry = minGapCountry == null ? gap : Math.min(minGapCountry, gap);
            }
        }

        // Doublons (meme marchand + meme montant) : on groupe, car deux transactions
        // identiques peuvent etre separees par d'autres transactions dans la tri.
        Integer minGapDuplicate = null;
        Map<String, List<TransactionView>> byKey = new HashMap<>();
        for (TransactionView t : sorted) {
            if (t.merchantId() != null && t.amount() != null) {
                byKey.computeIfAbsent(t.merchantId() + "|" + t.amount(), k -> new ArrayList<>()).add(t);
            }
        }
        for (List<TransactionView> group : byKey.values()) {
            for (int i = 1; i < group.size(); i++) {
                int gap = (int) ChronoUnit.MINUTES.between(
                        group.get(i - 1).transactionDate(), group.get(i).transactionDate());
                minGapDuplicate = minGapDuplicate == null ? gap : Math.min(minGapDuplicate, gap);
            }
        }

        // Nouveau pour CE client = marchand porte par la transaction que l'on n'a
        // jamais vu sur ses autres transactions. Un virement (merchantId null)
        // n'est PAS un "nouveau marchand".
        boolean seenBefore = self.merchantId() != null && sorted.stream()
                .filter(t -> !Objects.equals(t.transactionId(), self.transactionId()))
                .anyMatch(t -> Objects.equals(t.merchantId(), self.merchantId()));
        boolean newMerchantForCustomer = self.merchantId() != null && !seenBefore;

        return new TransactionFeatures.ActivityStats(
                txCount24h, cumul24hTnd, failedCount24h,
                smallTxCount24h, smallTxSum24h,
                minGapCountry, minGapDuplicate, newMerchantForCustomer);
    }

    /**
     * Transaction SORTANTE (l'argent quitte le compte) : PAYMENT, TRANSFER,
     * WITHDRAWAL. Un DEPOSIT n'est PAS de la structuration — il n'y a rien a
     * fragmenter quand l'argent entre.
     */
    private static boolean isOutgoing(String transactionType) {
        return switch (transactionType == null ? "" : transactionType) {
            case "PAYMENT", "TRANSFER", "WITHDRAWAL" -> true;
            default -> false;
        };
    }

    /** Percentile par rang nearest-rank sur une liste deja triee. */
    private static BigDecimal percentile(List<BigDecimal> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String modeCountry(List<TransactionView> sorted) {
        Map<String, Long> counts = new HashMap<>();
        for (TransactionView t : sorted) {
            if (t.country() != null) {
                counts.merge(t.country(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Heure habituelle : mode des heures LOCALES (risk.timezone). */
    private Integer modeLocalHour(List<TransactionView> sorted) {
        Map<Integer, Long> counts = new HashMap<>();
        for (TransactionView t : sorted) {
            int hour = t.transactionDate().atZoneSameInstant(riskZone).getHour();
            counts.merge(hour, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<Integer, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
