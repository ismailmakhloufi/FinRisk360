package tn.finrisk.alertcaseservice.web.dto;

import jakarta.validation.constraints.NotNull;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;

/**
 * Charge utile de POST /api/alerts.
 * severity est optionnelle : si absente, elle est derivee du verdict du
 * moteur (AlertService.severityFrom) — une seule source de verite.
 */
public record AlertCreateRequest(
        @NotNull(message = "assessmentId est obligatoire")
        Long assessmentId,
        @NotNull(message = "alertType est obligatoire")
        AlertType alertType,
        Severity severity,
        Long assignedTo) {
}
