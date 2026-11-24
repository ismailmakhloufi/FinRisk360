package tn.finrisk.accountservice.domain.enums;

/**
 * Etat d'un compte - CHECK IN accounts.status.
 * Pas de suppression dure : un compte passe de ACTIVE a FROZEN puis CLOSED.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
