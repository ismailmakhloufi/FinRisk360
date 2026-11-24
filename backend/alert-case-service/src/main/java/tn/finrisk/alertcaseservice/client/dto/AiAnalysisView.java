package tn.finrisk.alertcaseservice.client.dto;

/**
 * Vue reduite de POST /api/ai/alerts/{id}/analyze (ai-assistant-service,
 * port 8086). L'agent nocturne n'a besoin que du VERDICT de l'IA et de sa
 * confiance — le score vient du moteur (risk-service), pas de l'IA.
 *
 * Regle M0 : l'enum AiVerdict du distant est lue en String.
 * Verdicts : NORMAL / INCERTAIN / FRAUDE_SUSPECTE.
 */
public record AiAnalysisView(
        Long alertId,
        String verdict,
        Double confidence,
        String explanation) {
}
