package tn.finrisk.aiservice.web.dto;

/**
 * Réponse du chat. mode = comment la réponse a été produite :
 *   - DETERMINISTE : construite par le routeur à partir des données (défaut)
 *   - LLM          : confiée au modèle (AI_LLM_KEY définie)
 * intent expose l'intention reconnue — utile au front ET au jury
 * (on peut montrer « la question a été comprise comme X »).
 */
public record ChatResponse(
        Long alertId,
        String intent,
        String mode,
        String answer) {
}
