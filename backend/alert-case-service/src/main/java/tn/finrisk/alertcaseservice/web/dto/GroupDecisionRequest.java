package tn.finrisk.alertcaseservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Decision de l'analyste sur un GROUPE d'alertes apparentees.
 *
 *   ACCEPT — le pattern est legitime : groupe RESOLVED, alertes membres
 *            FALSE_POSITIVE, aucune transaction touchee.
 *   REFUSE — fraude confirmee : TOUTES les transactions du groupe sont
 *            annulees (et remboursees si deja reglees), groupe REFUSED,
 *            alertes membres RESOLVED.
 *
 * La saisie confirmee cote frontend (motif + tapez « ANNULER ») est une
 * garde UX ; cette couche re-valide la decision elle-meme (400 si inconnue).
 */
public record GroupDecisionRequest(
        @NotBlank(message = "decision obligatoire (ACCEPT ou REFUSE)")
        @Size(max = 10) String decision,
        @Size(max = 500) String reason) {
}
