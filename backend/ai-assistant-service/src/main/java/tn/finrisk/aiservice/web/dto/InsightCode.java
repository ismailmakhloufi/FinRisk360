package tn.finrisk.aiservice.web.dto;

/**
 * Le vocabulaire des signaux que l'IA sait détecter — chacun correspond à
 * un type d'analyse qui n'est PAS déjà couvert par les règles de
 * risk-service (sinon ce serait un doublon, pas un « regard en plus »).
 */
public enum InsightCode {

    /** Montant hors norme vs l'historique du client (statistiques). */
    AMOUNT_DEVIATION,

    /** Burst d'opérations sur une fenêtre courte (temporel). */
    VELOCITY_SPIKE,

    /** Même bénéficiaire que d'autres comptes — réseau (graph). */
    BENEFICIARY_NETWORK,

    /** Device déjà vu sur un autre compte (graph). */
    DEVICE_SHARED,

    /** IP déjà vue sur un autre compte (graph). */
    IP_SHARED,

    /** Premier virement vers ce bénéficiaire (rareté). */
    NEW_BENEFICIARY,

    /** Pays de la transaction différent du pays déclaré (géographie). */
    GEO_ANOMALY,

    /** Pas assez d'historique pour juger — dit au lieu de taire. */
    INSUFFICIENT_DATA
}
