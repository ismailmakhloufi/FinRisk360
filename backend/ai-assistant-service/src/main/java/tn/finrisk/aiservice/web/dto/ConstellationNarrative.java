package tn.finrisk.aiservice.web.dto;

import java.util.List;

/**
 * Narratif d'investigation en FRANÇAIS produit à partir du graphe :
 *   - LLM (Ollama) quand il est disponible : lecture libre, hypothèses
 *   - moteur déterministe en repli : récit bâti sur les insights + le graphe
 *
 * Jamais de décision : c'est un AVIS pour l'analyste, qui tranche.
 * focusNodes liste les ids de ConstellationNode à examiner en priorité
 * (le front les met en avant visuellement) — TOUJOURS validés contre les
 * nœuds réellement présents dans le graphe.
 *
 * dataQuality remplace l'ancienne « confiance » : celle-ci était calculée
 * à partir du nombre d'hypothèses et d'actions, ce qui mesurait la longueur
 * du texte, pas sa fiabilité. dataQuality décrit au contraire les DONNÉES
 * sur lesquelles l'avis s'appuie :
 *   COMPLETE  : scan complet, historique présent, client et comptes connus
 *   PARTIAL   : au moins un de ces manques (scan tronqué, historique vide…)
 *   DEGRADED  : transaction focale absente ou graphe réduit à l'alerte
 *
 * llmScore / llmScoreRationale : l'AVIS AUTONOME du LLM — un score 0-100
 * qu'il produit lui-même à partir des preuves des données (PAS une copie
 * du score moteur), justifié en une phrase. null en mode déterministe
 * (aucun LLM, aucun avis autonome) ou si le LLM n'a rien renvoyé d'utile.
 */
public record ConstellationNarrative(
        String summary,
        List<String> hypotheses,
        List<String> recommendedActions,
        List<String> focusNodes,
        String dataQuality,
        Integer llmScore,
        String llmScoreRationale,
        List<InvestigationCheck> checks) {

    public ConstellationNarrative(String summary, List<String> hypotheses,
            List<String> recommendedActions, List<String> focusNodes, String dataQuality,
            Integer llmScore, String llmScoreRationale) {
        this(summary, hypotheses, recommendedActions, focusNodes, dataQuality,
                llmScore, llmScoreRationale, List.of());
    }

    public record InvestigationCheck(String fact, String hypothesis, String alternative,
            String verification, List<EvidenceReference> evidence) {}

    public record EvidenceReference(String label, String detail, String edgeId) {}

    /** Signature d'avant l'avis autonome du LLM (sans llmScore). */
    public ConstellationNarrative(String summary, List<String> hypotheses,
            List<String> recommendedActions, List<String> focusNodes, String dataQuality) {
        this(summary, hypotheses, recommendedActions, focusNodes, dataQuality, null, null);
    }
}
