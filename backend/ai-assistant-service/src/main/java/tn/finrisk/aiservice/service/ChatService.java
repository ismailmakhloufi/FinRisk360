package tn.finrisk.aiservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tn.finrisk.aiservice.client.LlmClient;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.web.dto.ChatIntent;
import tn.finrisk.aiservice.web.dto.ChatRequest;
import tn.finrisk.aiservice.web.dto.ChatResponse;

/**
 * Chat sur l'alerte, deux modes dans le MÊME code :
 *
 *   AI_LLM_KEY absente  -> routeur déterministe (défaut, offline, testé)
 *   AI_LLM_KEY définie   -> LLM (prompt système = « n'invente pas, donne
 *                          les données ci-dessous ») ; si le LLM échoue,
 *                          repli immédiat sur le déterministe.
 *
 * Dans les deux cas, on analyse d'abord l'alerte (même chaîne que Analyze)
 * : le chat ne répond jamais sans contexte réel.
 *
 * Exception : les QUESTIONS DE DONNEES (« le compte 42 a-t-il payé le
 * bénéficiaire Y ? ») sont interceptées AVANT l'analyse d'alerte par le
 * DataQueryEngine (SQL lecture seule sur finrisk_ro) — elles ne dépendent
 * d'aucune alerte.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String SYSTEM_PROMPT = """
            Tu es l'assistant IA de FinRisk360, outil d'aide a l'analyse d'alertes
            pour analystes financiers. Tu réponds en français, en 3 phrases maximum,
            UNIQUEMENT a partir des donnees fournies. Tu n'inventes jamais de chiffre.
            Tu ne prends jamais de decision : tu fournis un avis, l'analyste tranche.
            N'affirme JAMAIS qu'une transaction est du blanchiment ou de la fraude :
            une alerte signale un seuil, pas un crime. Decris les faits, les signaux
            et les elements rassurants presents dans les donnees.
            Si l'information demandee n'est pas dans les donnees, dis-le simplement.
            """;

    private final AiAnalysisService analysisService;
    private final ConstellationGraphService graphService;
    private final ChatRouter router;
    private final LlmClient llmClient;
    private final DataQueryEngine dataQueryEngine;

    public ChatService(AiAnalysisService analysisService, ConstellationGraphService graphService,
            ChatRouter router, LlmClient llmClient, DataQueryEngine dataQueryEngine) {
        this.analysisService = analysisService;
        this.graphService = graphService;
        this.router = router;
        this.llmClient = llmClient;
        this.dataQueryEngine = dataQueryEngine;
    }

    public ChatResponse chat(ChatRequest request) {
        // Question de donnees (compte / beneficiaire / RIB) : SQL lecture
        // seule, AVANT l'analyse d'alerte (une question sur un compte
        // quelconque ne doit pas dependre de l'alerte ouverte).
        DataQueryEngine.QueryAnswer dataAnswer = dataQueryEngine.tryAnswer(request.message());
        if (dataAnswer != null) {
            return new ChatResponse(request.alertId(), ChatIntent.DATA_QUERY.name(),
                    dataAnswer.mode(), dataAnswer.answer());
        }

        FullAnalysis analysis = analysisService.analyzeFull(request.alertId());
        // Le graphe sert au routeur (intention constellation / expliquer) et
        // au prompt LLM : le chat répond aussi sur le RÉSEAU, pas seulement
        // sur la transaction isolée.
        var graph = graphService.build(analysis);

        if (llmClient.isEnabled()) {
            String answer = llmClient.complete(SYSTEM_PROMPT, contextSummary(analysis)
                    + constellationSummary(graph)
                    + "\n\nQuestion de l'analyste : " + request.message());
            if (answer != null && !answer.isBlank()) {
                return new ChatResponse(request.alertId(), ChatIntent.LLM.name(), "LLM", answer.trim());
            }
            log.warn("LLM sans réponse exploitable -> repli deterministe");
        }

        ChatRouter.RoutedReply reply = router.route(analysis, graph, request.message());
        return new ChatResponse(request.alertId(), reply.intent().name(), "DETERMINISTE", reply.answer());
    }

    /** Contexte compact injecté dans le prompt LLM (jamais des données brutes interminables). */
    static String contextSummary(FullAnalysis fa) {
        var r = fa.response();
        var tx = fa.context().transaction();
        StringBuilder sb = new StringBuilder();
        sb.append("Contexte de l'alerte ").append(fa.context().alert().alertCode()).append(" :\n");
        sb.append("- Score moteur : ").append(r.riskScore()).append("/100 -> ")
                .append(r.decision()).append("\n");
        sb.append("- Verdict IA : ").append(r.verdict())
                .append(" (confiance ").append(r.confidence()).append(")\n");
        sb.append("- Transaction : ").append(tx.amount()).append(" ")
                .append(tx.currency()).append(" vers ")
                .append(orDefault(tx.beneficiaryName(), "un beneficiaire non renseigne"))
                .append(" le ").append(tx.transactionDate())
                .append(" depuis ").append(orDefault(tx.country(), "pays inconnu")).append("\n");
        if (ConstellationGraphService.notBlank(tx.merchantName())) {
            sb.append("- Marchand : ").append(tx.merchantName())
                    .append(" (verifier la coherence du montant avec la nature ")
                    .append("probable de ce commerçant)\n");
        }
        var group = fa.context().alertGroup();
        if (group != null && group.groupId() != null) {
            sb.append("- Groupe d'alertes apparentees : #").append(group.groupId())
                    .append(" — ").append(group.relatedCount())
                    .append(" alertes, cumul ").append(group.totalAmount()).append(" TND en ")
                    .append(group.windowHours()).append("h, severite agregee ")
                    .append(group.severity())
                    .append(" (structuring / fractionnement a investiguer)\n");
        }
        sb.append("- Facteurs : ");
        var factors = fa.context().evaluation().factorsOrEmpty();
        if (factors.isEmpty()) {
            sb.append("aucun");
        } else {
            factors.forEach(f -> sb.append(f.ruleName()).append(" (+").append(f.factorScore())
                    .append(f.weight() == null ? "" : ", poids " + f.weight()).append("), "));
        }
        sb.append("\n- Signaux IA :\n");
        if (fa.insights().isEmpty()) {
            sb.append("  aucun\n");
        } else {
            fa.insights().forEach(i -> sb.append("  [").append(i.severity()).append("] ")
                    .append(i.message()).append(" (").append(i.evidence()).append(")\n"));
        }
        return sb.toString();
    }

    /** Mini-réseau (constellation) injecté dans le prompt LLM du chat. */
    static String constellationSummary(ConstellationGraphService.Graph graph) {
        long shared = graph.nodes().stream()
                .filter(n -> n.meta().get("sharedAccounts") instanceof java.util.Set<?> s
                        && !s.isEmpty())
                .count();
        StringBuilder sb = new StringBuilder();
        sb.append("- Reseau (constellation) : ").append(graph.nodes().size())
                .append(" entites, ").append(graph.edges().size())
                .append(" relations verifiables, ")
                .append(shared).append(" entite(s) partagee(s) avec d'autres comptes.\n");
        return sb.toString();
    }

    /** Jamais de « null » littéral dans le prompt : le LLM le recopierait tel quel. */
    static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() || "null".equals(value) ? fallback : value;
    }
}
