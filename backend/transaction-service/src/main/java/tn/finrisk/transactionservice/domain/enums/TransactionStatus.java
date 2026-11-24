package tn.finrisk.transactionservice.domain.enums;

/**
 * Etat du cycle de vie d'une transaction.
 * PENDING = recue, en attente d'evaluation de risque (M3).
 */
public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    HELD,
    /** Annulee par l'analyste (validation risque) — terminal. */
    CANCELLED
}
