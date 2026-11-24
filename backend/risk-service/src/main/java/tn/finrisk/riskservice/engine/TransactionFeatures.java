package tn.finrisk.riskservice.engine;

import java.math.BigDecimal;
import java.util.List;
import tn.finrisk.riskservice.client.dto.TransactionView;

/**
 * Toutes les donnees calculees par le Feature Engine pour une evaluation :
 * ce que les regles vont lire. Deux niveaux :
 *
 *  - HistoryStats  : la BASELINE du client (moyenne, ecart-type, P95...) calculee
 *                    sur l'historique 90j de TOUS ses comptes, SANS la transaction
 *                    evaluee (une fraude ne doit pas polluer sa propre baseline).
 *                    null si aucun antecedent => mode degrade (rules 003/006/007/008
 *                    neutralisees, ecrites en clair dans risk_factors).
 *
 *  - ActivityStats : fenetres glissantes ancrees sur l'heure de la transaction
 *                    (24h : velocite, cumul, echecs) et couples suspects
 *                    (pays different, doublon rapide).
 *
 *  localHour : heure LOCALE (risk.timezone = Africa/Tunis), pas UTC.
 */
public record TransactionFeatures(
        TransactionView transaction,
        Long customerId,
        HistoryStats stats,
        ActivityStats activity,
        Integer merchantAgeDays,
        int localHour,
        boolean tnd) {

    /** Statistiques de comportement sur la fenetre glissante du profil. */
    public record HistoryStats(
            int size,
            BigDecimal avg,
            BigDecimal sigma,
            BigDecimal median,
            BigDecimal max,
            BigDecimal p95,
            BigDecimal p99,
            BigDecimal transactionsPerDay,
            String usualCountry,
            Integer usualHour,
            Integer usualMerchantCount) {
    }

    /**
     * Activite recente et couples temporels suspects.
     *
     * smallTxCount24h / smallTxSum24h : la STRUCTURATION. On ne compte pas les
     * transactions « grosses » (cela, RULE-001 le fait deja) mais les transactions
     * PETITES et SORTANTES de la fenetre 24h. C'est le signal classique de l'AML :
     * un fraudeur fractionne un montant en N verses sous le seuil de declaration.
     * Chaque versement pris isolement est anodin (score ~5), c'est le MOTIF qui
     * est suspect — d'ou une regle dediee (RULE-012) plutot qu'un seuil de montant.
     */
    public record ActivityStats(
            int txCount24h,
            BigDecimal cumul24hTnd,
            int failedCount24h,
            int smallTxCount24h,
            BigDecimal smallTxSum24h,
            Integer minGapCountryMinutes,
            Integer minGapDuplicateMinutes,
            boolean newMerchantForCustomer) {
    }

    /** Faux => pas d'antecedent : les regles qui besoin d'une baseline sont neutralisees. */
    public boolean hasBaseline() {
        return stats != null && stats.size() > 0;
    }
}
