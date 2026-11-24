package tn.finrisk.alertcaseservice.web.dto;

import tn.finrisk.alertcaseservice.domain.AuditLog;
import java.time.OffsetDateTime;

/** Reponse rendue pour une entree d'audit (la trace exigee par M4). */
public record AuditResponse(
        Long auditId,
        Long userId,
        String action,
        String entityType,
        Long entityId,
        String oldValue,
        String newValue,
        OffsetDateTime createdAt,
        String ipAddress) {

    public static AuditResponse from(AuditLog entry) {
        return new AuditResponse(
                entry.getAuditId(),
                entry.getUserId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getCreatedAt(),
                entry.getIpAddress());
    }
}
