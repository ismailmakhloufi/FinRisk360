package tn.finrisk.alertcaseservice.domain.enums;

/**
 * Cycle de vie d'un GROUPE d'alertes (distinct du cycle de vie d'une alerte
 * individuelle : AlertStatus). Un groupe n'a pas d'enquete propre — c'est un
 * AGREGAT de lecture, pas un dossier. Il s'ouvre avec la premiere alerte et
 * se clot quand l'analyste a tranche sur le pattern :
 *
 *   OPEN ──> RESOLVED     (pattern accepte : legitime, alertes -> FALSE_POSITIVE)
 *        ──> REFUSED      (fraude confirmee : transactions annulees et
 *                          remboursees, alertes -> RESOLVED)
 *        ──> FALSE_POSITIVE (fermeture sans suite)
 */
public enum GroupStatus {
    OPEN,
    RESOLVED,
    FALSE_POSITIVE,
    /** Refuse par l'analyste : annulation + remboursement des transactions. */
    REFUSED;

    /** Etat terminal : aucune alerte nouvelle ne doit plus y etre rattachee. */
    public boolean isTerminal() {
        return this != OPEN;
    }
}
