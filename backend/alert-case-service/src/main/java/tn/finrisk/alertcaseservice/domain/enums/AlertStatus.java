package tn.finrisk.alertcaseservice.domain.enums;

/**
 * Cycle de vie d'une alerte (check ck_alerts_status) :
 *
 *   OPEN ──> ACKNOWLEDGED ──> IN_PROGRESS ──> RESOLVED
 *                   │               │
 *                   └───────────────┴──────> FALSE_POSITIVE
 *
 *   OPEN (2e alerte d'un meme compte en 24h) ──> ABSORBED
 *
 * ABSORBED : l'alerte a ete ABSORBEE par son groupe d'agregation —
 * la 1re alerte du compte reste OPEN (point d'entree), les suivantes
 * sont masquees de la file principale mais restent consultables comme
 * membres du groupe (trace regulamentaire). Etat terminal : on traite
 * le GROUPE, pas l'alerte absorbee.
 *
 * La machine a etats vit ICI (dans l'enum) : un seul endroit a verifier,
 * pur, testable sans base ni HTTP. Toute transition hors graphe -> 409.
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    IN_PROGRESS,
    RESOLVED,
    FALSE_POSITIVE,
    /** Absorbee par un groupe d'agregation : masquee de la file, terminal. */
    ABSORBED;

    /** Transition legale depuis cet etat ? */
    public boolean canMoveTo(AlertStatus target) {
        return switch (this) {
            case OPEN -> target == ACKNOWLEDGED;
            case ACKNOWLEDGED -> target == IN_PROGRESS || target == FALSE_POSITIVE;
            case IN_PROGRESS -> target == RESOLVED || target == FALSE_POSITIVE;
            case RESOLVED, FALSE_POSITIVE, ABSORBED -> false; // etats terminaux
        };
    }

    /** Etat terminal : plus aucune transition, resolved_at renseigne. */
    public boolean isTerminal() {
        return this == RESOLVED || this == FALSE_POSITIVE || this == ABSORBED;
    }
}
