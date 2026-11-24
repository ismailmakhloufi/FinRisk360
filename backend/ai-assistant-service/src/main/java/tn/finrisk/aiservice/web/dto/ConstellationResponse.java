package tn.finrisk.aiservice.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Réponse de POST /api/ai/alerts/{id}/constellation — le graphe d'investigation
 * complet : entités réelles (nodes), relations vérifiables (edges), le
 * narratif français de l'IA (ou repli déterministe) et le POURQUOI de l'alerte.
 *
 * mode : "LLM" si Ollama a produit le narratif, "DETERMINISTE" sinon.
 * Le graphe (nodes/edges) est TOUJOURS déterministe — seule l'interprétation
 * change selon le mode. Aucune décision n'est prise ici.
 *
 * truncated / hiddenSharedAccounts : le graphe est BORNÉ (MAX_SHARED_ACCOUNTS)
 * pour rester lisible. Plutôt que de laisser l'analyste croire que le réseau
 * s'arrête là, on DÉCLARE ce qui a été écarté.
 *
 * scoreFactors : les facteurs du moteur (règle, points, poids) qui expliquent
 * le score — affichés dans le panneau « POURQUOI CETTE ALERTE ? ».
 * readingLine : une phrase déterministe « lecture du graphe » générée à
 * partir des arêtes réelles (jamais inventée, jamais LLM).
 */
public record ConstellationResponse(
        Long alertId,
        String alertCode,
        List<ConstellationNode> nodes,
        List<ConstellationEdge> edges,
        ConstellationNarrative narrative,
        String mode,
        boolean truncated,
        int hiddenSharedAccounts,
        List<ScoreFactor> scoreFactors,
        String readingLine,
        OffsetDateTime generatedAt) {

    /** Signature d'avant les compteurs de troncature. */
    public ConstellationResponse(Long alertId, String alertCode, List<ConstellationNode> nodes,
            List<ConstellationEdge> edges, ConstellationNarrative narrative, String mode,
            OffsetDateTime generatedAt) {
        this(alertId, alertCode, nodes, edges, narrative, mode, false, 0,
                List.of(), null, generatedAt);
    }

    /** Signature d'avant scoreFactors / readingLine. */
    public ConstellationResponse(Long alertId, String alertCode, List<ConstellationNode> nodes,
            List<ConstellationEdge> edges, ConstellationNarrative narrative, String mode,
            boolean truncated, int hiddenSharedAccounts, OffsetDateTime generatedAt) {
        this(alertId, alertCode, nodes, edges, narrative, mode, truncated, hiddenSharedAccounts,
                List.of(), null, generatedAt);
    }
}
