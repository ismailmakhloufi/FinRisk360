package tn.finrisk.alertcaseservice.domain.enums;

/**
 * Cycle de vie d'une case d'enquete (check ck_cases_status) :
 *
 *   OPEN ──> UNDER_REVIEW ──> CONFIRMED ──> CLOSED
 *                   │
 *                   ├──────> CLOSED
 *                   └──────> FALSE_POSITIVE
 *
 * CLOSED et FALSE_POSITIVE sont terminaux : closed_at est renseigne.
 * Une fraude confirmee (CONFIRMED) reste ouverte jusqu'a sa cloture.
 */
public enum CaseStatus {
    OPEN,
    UNDER_REVIEW,
    CONFIRMED,
    FALSE_POSITIVE,
    CLOSED;

    /** Transition legale depuis cet etat ? */
    public boolean canMoveTo(CaseStatus target) {
        return switch (this) {
            case OPEN -> target == UNDER_REVIEW;
            case UNDER_REVIEW -> target == CONFIRMED
                    || target == FALSE_POSITIVE
                    || target == CLOSED;
            case CONFIRMED -> target == CLOSED;
            case FALSE_POSITIVE, CLOSED -> false; // etats terminaux
        };
    }

    /** Etat terminal : closed_at renseigne, plus aucune mutation. */
    public boolean isTerminal() {
        return this == CLOSED || this == FALSE_POSITIVE;
    }
}
