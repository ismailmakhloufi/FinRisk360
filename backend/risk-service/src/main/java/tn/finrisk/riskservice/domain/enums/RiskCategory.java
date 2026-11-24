package tn.finrisk.riskservice.domain.enums;

/**
 * Famille de regle / de facteur (checks SQL ck_rr_category et ck_rf_type,
 * qui imposent exactement les memes valeurs).
 * AMOUNT      : montant anormal
 * VELOCITY    : trop de transactions en peu de temps
 * GEOGRAPHIC  : pays inhabituel pour le client
 * TIME        : heure inhabituelle
 * BEHAVIOR    : marchand nouveau, canal, device...
 * HISTORY     : derive statistique vs historique (P95, cumul 24h...)
 */
public enum RiskCategory {
    AMOUNT,
    VELOCITY,
    GEOGRAPHIC,
    TIME,
    BEHAVIOR,
    HISTORY
}
