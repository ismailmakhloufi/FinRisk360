package tn.finrisk.aiservice.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Arête du graphe d'investigation — UNE relation VÉRIFIABLE entre deux
 * entités réelles. Chaque arête porte sa preuve (evidence) : l'analyste
 * peut toujours expliquer pourquoi deux nœuds sont reliés.
 *
 *   source / target : ids de ConstellationNode
 *   type  : OWNS | SENT | TO | USED_DEVICE | FROM_IP | IN_COUNTRY | SHARED_WITH
 *   label : libellé français affiché sur l'arête (« possède », « même device »)
 *   evidence : la preuve chiffrée / la source de la relation
 *   weight : 1 (faible) à 5 (lien fort) — épaisseur de la ligne côté front
 */
public record ConstellationEdge(
        String id,
        String source,
        String target,
        String type,
        String label,
        String evidence,
        int weight,
        List<ProofTransaction> proofTransactions) {

    public ConstellationEdge(String id, String source, String target, String type,
            String label, String evidence, int weight) {
        this(id, source, target, type, label, evidence, weight, List.of());
    }

    public record ProofTransaction(long transactionId, OffsetDateTime date) {}

    /** id technique déterministe : source->target-type (stable entre appels). */
    public static String edgeId(String source, String target, String type) {
        return source + "->" + target + ":" + type;
    }
}
