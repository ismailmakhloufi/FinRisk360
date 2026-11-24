package tn.finrisk.alertcaseservice.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tn.finrisk.alertcaseservice.domain.AlertGroup;
import tn.finrisk.alertcaseservice.domain.enums.GroupScope;
import tn.finrisk.alertcaseservice.domain.enums.GroupStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;

/**
 * Reponse rendue pour un GROUPE d'alertes apparentees.
 *
 * C'est le point ou l'analyste voit enfin le PATTERN : "12 alertes apparentes
 * sur le compte 101, cumul 1200.00 TND en 24h, severite agrégée HIGH". Chaque
 * alerte du groupe reste lisible separement (memberAlertIds).
 */
public record AlertGroupResponse(
        Long groupId,
        GroupScope scopeType,
        String scopeValue,
        Long customerId,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        int windowHours,
        Severity severity,
        GroupStatus status,
        int relatedCount,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<Long> memberAlertIds) {

    public static AlertGroupResponse from(AlertGroup g) {
        return new AlertGroupResponse(
                g.getGroupId(),
                g.getScopeType(),
                g.getScopeValue(),
                g.getCustomerId(),
                g.getWindowStart(),
                g.windowEnd(),
                g.getWindowHours(),
                g.getSeverity(),
                g.getStatus(),
                g.getRelatedCount(),
                g.getTotalAmount(),
                g.getCreatedAt(),
                g.getUpdatedAt(),
                List.of());
    }

    /** Variante enrichie avec les ids des alertes membres. */
    public static AlertGroupResponse from(AlertGroup g, List<Long> memberAlertIds) {
        AlertGroupResponse base = from(g);
        return new AlertGroupResponse(
                base.groupId(), base.scopeType(), base.scopeValue(), base.customerId(),
                base.windowStart(), base.windowEnd(), base.windowHours(),
                base.severity(), base.status(), base.relatedCount(), base.totalAmount(),
                base.createdAt(), base.updatedAt(), memberAlertIds);
    }
}
