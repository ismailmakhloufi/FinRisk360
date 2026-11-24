package tn.finrisk.aiservice.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Réponse de POST /api/ai/alerts/{id}/analyze — le score du moteur et
 * l'avis de l'IA CÔTE À CÔTE, plus l'explication qui les relie :
 * l'analyste voit d'où vient le 87, ce que l'IA a trouvé EN PLUS,
 * et pourquoi son verdict est ce qu'il est.
 */
public record AnalysisResponse(
        Long alertId,
        Long assessmentId,
        BigDecimal riskScore,
        String riskLevel,
        String decision,
        AiVerdict verdict,
        double confidence,
        List<Insight> insights,
        String explanation,
        OffsetDateTime analyzedAt) {
}
