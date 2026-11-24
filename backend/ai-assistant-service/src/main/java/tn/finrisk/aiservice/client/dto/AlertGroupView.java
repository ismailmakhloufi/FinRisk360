package tn.finrisk.aiservice.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Groupe d'alertes apparentées lu CHEZ alert-case-service
 * (GET /api/alert-groups/{id}). Porte le pattern agrégé : cumul, compteur,
 * sévérité escaladée par le nombre. Enums en String (règle M0).
 */
public record AlertGroupView(
        Long groupId,
        String scopeType,
        String scopeValue,
        Long customerId,
        OffsetDateTime windowStart,
        int windowHours,
        String severity,
        String status,
        int relatedCount,
        BigDecimal totalAmount) {
}
