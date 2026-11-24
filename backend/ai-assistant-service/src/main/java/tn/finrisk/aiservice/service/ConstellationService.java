package tn.finrisk.aiservice.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tn.finrisk.aiservice.client.dto.FactorView;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.service.ConstellationGraphService.Graph;
import tn.finrisk.aiservice.service.ConstellationNarrativeService.Narration;
import tn.finrisk.aiservice.web.dto.ConstellationNode;
import tn.finrisk.aiservice.web.dto.ConstellationResponse;
import tn.finrisk.aiservice.web.dto.ScoreFactor;

/**
 * Façade de la constellation : relie la construction DÉTERMINISTE du graphe
 * et le narratif (LLM ou repli) en une seule réponse prête pour le front.
 *
 * Une seule passe d'analyse (analyzeFull) réutilise la chaîne existante —
 * aucune duplication de lecture des services distants.
 *
 * Elle ajoute deux éléments « pour expliquer » (100% déterministes) :
 *   - scoreFactors : les règles du moteur avec POINTS et POIDS, pour le
 *     panneau « POURQUOI CETTE ALERTE ? ».
 *   - readingLine : une phrase de lecture bâtie sur les arêtes RÉELLES du
 *     graphe (jamais inventée, jamais produite par le LLM).
 */
@Service
public class ConstellationService {

    private final AiAnalysisService analysisService;
    private final ConstellationGraphService graphService;
    private final ConstellationNarrativeService narrativeService;

    public ConstellationService(AiAnalysisService analysisService,
            ConstellationGraphService graphService,
            ConstellationNarrativeService narrativeService) {
        this.analysisService = analysisService;
        this.graphService = graphService;
        this.narrativeService = narrativeService;
    }

    /** POST /api/ai/alerts/{alertId}/constellation */
    public ConstellationResponse constellation(Long alertId) {
        FullAnalysis fa = analysisService.analyzeFull(alertId);
        Graph graph = graphService.build(fa);
        Narration narration = narrativeService.narrate(fa, graph);

        return new ConstellationResponse(
                fa.context().alert().alertId(),
                fa.context().alert().alertCode(),
                graph.nodes(),
                graph.edges(),
                narration.narrative(),
                narration.mode(),
                graph.truncated(),
                graph.hiddenSharedAccounts(),
                scoreFactors(fa),
                readingLine(fa, graph),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Facteurs du score moteur triés par points décroissants — ce que
     * l'analyste lit dans « POURQUOI CETTE ALERTE ? ». Jamais de décision,
     * juste la restitution du raisonnement de risk-service.
     */
    static List<ScoreFactor> scoreFactors(FullAnalysis fa) {
        return fa.context().evaluation().factorsOrEmpty().stream()
                .sorted((a, b) -> nullScore(b).compareTo(nullScore(a)))
                .map(f -> new ScoreFactor(f.ruleCode(), f.ruleName(),
                        f.factorScore(), f.weight(), f.reason()))
                .toList();
    }

    /**
     * Phrase de lecture déterministe, construite UNIQUEMENT à partir du
     * graphe réel et du contexte d'alerte :
     *   1. qui a émis quoi, vers qui, quand (transaction focale)
     *   2. les entités partagées (preuves vérifiables)
     *   3. le groupe d'alertes apparentées s'il existe
     *   4. le score moteur et ses principaux facteurs
     * Reproductible à l'identique à chaque appel.
     */
    static String readingLine(FullAnalysis fa, Graph graph) {
        var ctx = fa.context();
        StringBuilder s = new StringBuilder();

        String account = graph.nodes().stream()
                .filter(n -> "ACCOUNT".equals(n.type()))
                .filter(ConstellationNode::investigationFocus)
                .map(ConstellationNode::label)
                .findFirst()
                .orElseGet(() -> graph.nodes().stream()
                        .filter(n -> "ACCOUNT".equals(n.type()))
                        .map(ConstellationNode::label)
                        .findFirst().orElse(null));
        String customer = graph.nodes().stream()
                .filter(n -> "CUSTOMER".equals(n.type()))
                .map(ConstellationNode::label)
                .findFirst().orElse(null);

        var tx = ctx.transaction();
        if (tx != null && account != null) {
            s.append(account);
            if (customer != null) {
                s.append(" (").append(customer).append(")");
            }
            s.append(" a émis ")
                    .append(ConstellationGraphService.formatAmount(tx.amount(), tx.currency()))
                    .append(" vers « ")
                    .append(ConstellationGraphService.orUnknown(tx.beneficiaryName()))
                    .append("» le ").append(tx.transactionDate()).append(". ");
        } else if (tx != null) {
            s.append("Transaction focale de ")
                    .append(ConstellationGraphService.formatAmount(tx.amount(), tx.currency()))
                    .append(" vers « ")
                    .append(ConstellationGraphService.orUnknown(tx.beneficiaryName()))
                    .append("» le ").append(tx.transactionDate()).append(". ");
        }

        long shared = graph.nodes().stream()
                .filter(n -> n.meta().get("sharedAccounts") instanceof Set<?> s2 && !s2.isEmpty())
                .count();
        if (shared > 0) {
            s.append(shared).append(" entité(s) partagée(s) avec d'autres comptes "
                    + "(preuve vérifiable : bénéficiaire, appareil ou IP commun). ");
        }

        var grp = ctx.alertGroup();
        if (grp != null && grp.groupId() != null) {
            s.append("Groupe #").append(grp.groupId()).append(" : ")
                    .append(grp.relatedCount()).append(" alerte(s) apparentée(s), cumul ")
                    .append(ConstellationGraphService.formatAmount(grp.totalAmount(), "TND"))
                    .append(" en ").append(grp.windowHours()).append("h. ");
        }

        var ev = ctx.evaluation();
        List<FactorView> top = ev.factorsOrEmpty().stream()
                .filter(f -> f.factorScore() != null && f.factorScore().signum() > 0)
                .sorted((a, b) -> b.factorScore().compareTo(a.factorScore()))
                .limit(2).toList();
        s.append("Score moteur ").append(ev.riskScore()).append("/100 → ")
                .append(ev.decision());
        if (!top.isEmpty()) {
            s.append(", principalement ")
                    .append(top.stream()
                            .map(f -> f.ruleName() + " (+" + f.factorScore() + ")")
                            .collect(Collectors.joining(" et ")));
        }
        s.append(".");
        return s.toString();
    }

    private static BigDecimal nullScore(FactorView f) {
        return f.factorScore() == null ? BigDecimal.ZERO : f.factorScore();
    }
}
