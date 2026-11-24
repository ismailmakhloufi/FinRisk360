package tn.finrisk.aiservice.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.dto.AnalysisContext;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Le cerveau M6, 100% déterministe : des CALCULS sur des données réelles,
 * pas un LLM qui improvise. Chaque insight est reproductible — en soutenance,
 * on montre le chiffre, pas une opinion.
 *
 * Quatre familles de détection (aucune n'est un doublon des règles M3) :
 *   1. Statistiques : écart au P95 de l'historique du client
 *   2. Temporel     : burst d'opérations sur 60 min vs rythme habituel
 *   3. Graph        : bénéficiaire/device/IP partagés entre comptes
 *   4. Contexte     : bénéficiaire inédit, pays incohérent
 *
 * Toutes les seuilleries sont des constantes nommées : le jury demande
 * « pourquoi 3× ? » → on répond, c'est écrit et testé.
 *
 * Classe PURA : ni RestClient, ni repository — testable hors contexte.
 * Exposée comme bean pour que l'orchestrateur l'injecte.
 */
@Component
public class InsightEngine {

    /** Minimum d'historique pour oser des stats (sinon on le DIT, voir INSUFFICIENT_DATA). */
    static final int MIN_HISTORY = 5;

    /** Écart au-dessus du P95 considéré comme hors norme. */
    static final double DEVIATION_HIGH = 3.0;
    static final double DEVIATION_MEDIUM = 1.5;

    /** Fenêtre de vélocité (burst = N opérations en 60 minutes). */
    static final Duration VELOCITY_WINDOW = Duration.ofMinutes(60);
    static final int VELOCITY_HIGH = 4;
    static final int VELOCITY_MEDIUM = 3;

    /** Nombre d' AUTRES comptes touchés pour parler de réseau. */
    static final int NETWORK_HIGH = 2;
    static final int NETWORK_MEDIUM = 1;

    public List<Insight> analyze(AnalysisContext ctx) {
        List<Insight> out = new ArrayList<>();
        List<TransactionView> history = ctx.history() == null ? List.of() : ctx.history();
        List<TransactionView> scan = ctx.globalScan() == null ? List.of() : ctx.globalScan();

        boolean enoughHistory = history.size() >= MIN_HISTORY;
        if (!enoughHistory) {
            out.add(new Insight(InsightCode.INSUFFICIENT_DATA, InsightSeverity.LOW,
                    "Historique trop court pour des statistiques fiables.",
                    "[transactions] " + history.size() + " opération(s) disponibles, "
                            + MIN_HISTORY + " minimum"));
        }

        TransactionView tx = ctx.transaction();
        if (tx != null) {
            if (enoughHistory) {
                checkAmountDeviation(tx, history, out);
                checkNewBeneficiary(tx, history, out);
            }
            checkVelocity(tx, history, out);
            checkGraph(tx, scan, out);
            checkGeography(tx, ctx.customer(), out);
        }

        // Tri déterministe : HIGH d'abord, puis par code — même sortie à chaque appel.
        out.sort(Comparator.comparing(Insight::severity).reversed()
                .thenComparing(i -> i.code().name()));
        return out;
    }

    // ---------------------------------------------------------------- stats

    /**
     * Écart au P95 (95e percentile) de l'historique : le chiffre que
     * l'analyste n'a jamais sous les yeux en ouvrant une fiche.
     * ratio = montant / P95 ; seuls les montants strictement positifs comptent.
     */
    private void checkAmountDeviation(TransactionView tx, List<TransactionView> history, List<Insight> out) {
        if (tx.amount() == null) {
            return;
        }
        List<BigDecimal> amounts = history.stream()
                .map(TransactionView::amount)
                .filter(Objects::nonNull)
                .filter(a -> a.signum() > 0)
                .sorted()
                .toList();
        if (amounts.isEmpty()) {
            return;
        }
        BigDecimal p95 = percentile(amounts, 95);
        if (p95.signum() <= 0) {
            return;
        }
        double ratio = tx.amount().doubleValue() / p95.doubleValue();
        if (ratio >= DEVIATION_HIGH) {
            out.add(new Insight(InsightCode.AMOUNT_DEVIATION, InsightSeverity.HIGH,
                    "Montant " + plain(tx.amount()) + " = " + fmt1(ratio)
                            + "× le P95 de votre historique — hors norme.",
                    "[transactions] amount=" + plain(tx.amount()) + ", p95=" + plain(p95)
                            + ", ratio=" + fmt1(ratio) + ", n=" + amounts.size()));
        } else if (ratio >= DEVIATION_MEDIUM) {
            out.add(new Insight(InsightCode.AMOUNT_DEVIATION, InsightSeverity.MEDIUM,
                    "Montant " + plain(tx.amount()) + " = " + fmt1(ratio)
                            + "× le P95 (" + plain(p95) + ") — au-dessus de l'habitude.",
                    "[transactions] amount=" + plain(tx.amount()) + ", p95=" + plain(p95)
                            + ", ratio=" + fmt1(ratio) + ", n=" + amounts.size()));
        }
    }

    // ------------------------------------------------------------ temporel

    /**
     * Burst : combien d'opérations sur le MÊME compte dans la fenêtre de
     * 60 min précédant la transaction, comparé au rythme quotidien moyen.
     */
    private void checkVelocity(TransactionView tx, List<TransactionView> history, List<Insight> out) {
        if (tx.transactionDate() == null || tx.accountId() == null) {
            return;
        }
        OffsetDateTime windowStart = tx.transactionDate().minus(VELOCITY_WINDOW);
        List<TransactionView> sameAccount = history.stream()
                .filter(t -> tx.accountId().equals(t.accountId()))
                .filter(t -> t.transactionDate() != null)
                .toList();
        long inWindow = sameAccount.stream()
                .filter(t -> !t.transactionDate().isBefore(windowStart)
                        && !t.transactionDate().isAfter(tx.transactionDate()))
                .count();

        if (inWindow < VELOCITY_MEDIUM) {
            return;
        }
        // Rythme habituel : opérations/jour sur la période couverte par l'historique.
        double dailyRate = dailyRate(sameAccount);
        if (inWindow >= VELOCITY_HIGH || inWindow > Math.max(dailyRate, 1.0)) {
            InsightSeverity severity = inWindow >= VELOCITY_HIGH
                    ? InsightSeverity.HIGH : InsightSeverity.MEDIUM;
            out.add(new Insight(InsightCode.VELOCITY_SPIKE, severity,
                    inWindow + " opérations sur " + VELOCITY_WINDOW.toMinutes()
                            + " min (rythme habituel : " + fmt1(dailyRate) + "/jour).",
                    "[transactions] window=" + inWindow + "/" + VELOCITY_WINDOW.toMinutes()
                            + "min, dailyAvg=" + fmt1(dailyRate)
                            + ", accountId=" + tx.accountId()));
        }
    }

    // ---------------------------------------------------------------- graph

    /**
     * Croisement que l'humain ne fait jamais : mêmes bénéficiaires/device/IP
     * sur d'AUTRES comptes (échantillon global). C'est la détection réseau.
     */
    private void checkGraph(TransactionView tx, List<TransactionView> scan, List<Insight> out) {
        matchOtherAccounts(tx, scan, tx.beneficiaryName(), TransactionView::beneficiaryName,
                true, InsightCode.BENEFICIARY_NETWORK, "bénéficiaire", out);
        matchOtherAccounts(tx, scan, tx.deviceId(), TransactionView::deviceId,
                false, InsightCode.DEVICE_SHARED, "device", out);
        matchOtherAccounts(tx, scan, tx.ipAddress(), TransactionView::ipAddress,
                false, InsightCode.IP_SHARED, "IP", out);
    }

    private void matchOtherAccounts(TransactionView tx, List<TransactionView> scan,
            String value, Function<TransactionView, String> extractor, boolean caseInsensitive,
            InsightCode code, String label, List<Insight> out) {
        if (value == null || value.isBlank() || tx.accountId() == null) {
            return;
        }
        Set<Long> others = scan.stream()
                .filter(t -> !Objects.equals(t.transactionId(), tx.transactionId()))
                .filter(t -> matches(value, extractor.apply(t), caseInsensitive))
                .map(TransactionView::accountId)
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(tx.accountId()))
                .collect(Collectors.toSet());
        if (others.isEmpty()) {
            return;
        }
        InsightSeverity severity = others.size() >= NETWORK_HIGH
                ? InsightSeverity.HIGH : InsightSeverity.MEDIUM;
        out.add(new Insight(code, severity,
                "Le " + label + " '" + value.trim() + "' apparaît sur "
                        + others.size() + " autre(s) compte(s) — "
                        + (others.size() >= NETWORK_HIGH ? "réseau suspect." : "à vérifier."),
                "[graph] " + label + "=" + value.trim() + ", otherAccounts=" + others));
    }

    // -------------------------------------------------------------- contexte

    /** Premier virement vers ce bénéficiaire sur la période observée. */
    private void checkNewBeneficiary(TransactionView tx, List<TransactionView> history, List<Insight> out) {
        String beneficiary = tx.beneficiaryName();
        if (beneficiary == null || beneficiary.isBlank()) {
            return;
        }
        long seen = history.stream()
                .filter(t -> !Objects.equals(t.transactionId(), tx.transactionId()))
                .filter(t -> matches(beneficiary, t.beneficiaryName(), true))
                .count();
        if (seen == 0) {
            out.add(new Insight(InsightCode.NEW_BENEFICIARY, InsightSeverity.MEDIUM,
                    "Premier virement vers '" + beneficiary.trim() + "' sur les "
                            + history.size() + " dernières opérations.",
                    "[transactions] occurrences=0/" + Math.max(history.size() - 1, 0)
                            + ", beneficiary=" + beneficiary.trim()));
        }
    }

    /** Pays de la transaction != pays déclaré du profil. */
    private void checkGeography(TransactionView tx, CustomerView customer, List<Insight> out) {
        if (customer == null || customer.country() == null || customer.country().isBlank()
                || tx.country() == null || tx.country().isBlank()) {
            return;
        }
        if (!customer.country().trim().equalsIgnoreCase(tx.country().trim())) {
            out.add(new Insight(InsightCode.GEO_ANOMALY, InsightSeverity.MEDIUM,
                    "Transaction depuis '" + tx.country() + "' alors que le client est déclaré '"
                            + customer.country() + "'.",
                    "[customers] customerCountry=" + customer.country()
                            + ", txCountry=" + tx.country()));
        }
    }

    // --------------------------------------------------------------- outils

    /** Percentile par rang (méthode du haut) sur une liste TRIÉE croissante. */
    static BigDecimal percentile(List<BigDecimal> sortedAscending, int pct) {
        int n = sortedAscending.size();
        int rank = (int) Math.ceil(pct / 100.0 * n);
        int index = Math.min(Math.max(rank - 1, 0), n - 1);
        return sortedAscending.get(index);
    }

    /** Opérations/jour sur la période couverte (au moins 1 jour). */
    static double dailyRate(List<TransactionView> accountHistory) {
        List<OffsetDateTime> dates = accountHistory.stream()
                .map(TransactionView::transactionDate)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (dates.size() < 2) {
            return dates.size();
        }
        long days = Math.max(Duration.between(dates.getFirst(), dates.getLast()).toDays(), 1);
        return (double) dates.size() / days;
    }

    /** Comparaison de libellés : null-safe, trim, casse si demandé. */
    static boolean matches(String a, String b, boolean caseInsensitive) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return false;
        }
        return caseInsensitive
                ? a.trim().equalsIgnoreCase(b.trim())
                : a.trim().equals(b.trim());
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
