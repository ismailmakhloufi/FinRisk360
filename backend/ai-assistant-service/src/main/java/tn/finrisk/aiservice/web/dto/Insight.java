package tn.finrisk.aiservice.web.dto;

/**
 * Un signal détecté par l'IA — LA chose que l'analyste ne voit pas seul :
 * il ouvre une alerte à la fois, l'IA croise des milliers de lignes.
 *
 * Trois champs, trois promesses :
 *   - code    : quel type de détection (enum stable pour le front)
 *   - message : l'interprétation en langage analyste
 *   - evidence: LA PREUVE chiffrée + la source [transactions]/[graph]/...
 *               → rien n'est affirmé sans donnée vérifiable derrière
 */
public record Insight(
        InsightCode code,
        InsightSeverity severity,
        String message,
        String evidence) {
}
