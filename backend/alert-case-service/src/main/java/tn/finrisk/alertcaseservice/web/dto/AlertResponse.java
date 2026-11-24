package tn.finrisk.alertcaseservice.web.dto;

import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Reponse rendue pour une alerte (jamais l'entite brute).
 *
 * groupId : le GROUPE d'alertes apparentees, si l'agregation a pu se faire
 * (NULL sinon — p. ex. transaction-service injoignable a la creation).
 * amount  : montant copie de la transaction, pour calculer le cumul du groupe
 * sans re-interroger transaction-service.
 */
public record AlertResponse(
        Long alertId,
        Long assessmentId,
        String alertCode,
        AlertType alertType,
        Severity severity,
        AlertStatus status,
        Long assignedTo,
        Long groupId,
        BigDecimal amount,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt) {

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getAlertId(),
                alert.getAssessmentId(),
                alert.getAlertCode(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getAssignedTo(),
                alert.getGroupId(),
                alert.getAmount(),
                alert.getCreatedAt(),
                alert.getResolvedAt());
    }
}
