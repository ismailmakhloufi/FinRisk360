package tn.finrisk.aiservice.client.dto;

import java.time.OffsetDateTime;

/**
 * Alerte lue CHEZ alert-case-service (GET /api/alerts/{id} et liste) :
 * l'objet d'analyse. assessmentId fait le pont vers risk-service,
 * le reste décrit le contexte (type, sévérité, statut). Enums en String.
 */
public record AlertView(
        Long alertId,
        Long assessmentId,
        String alertCode,
        String alertType,
        String severity,
        String status,
        Long assignedTo,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        Long groupId) {

    /** Signature d'avant groupId (alertes hors agrégation M5). */
    public AlertView(Long alertId, Long assessmentId, String alertCode, String alertType,
            String severity, String status, Long assignedTo,
            OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        this(alertId, assessmentId, alertCode, alertType, severity, status, assignedTo,
                createdAt, resolvedAt, null);
    }
}
