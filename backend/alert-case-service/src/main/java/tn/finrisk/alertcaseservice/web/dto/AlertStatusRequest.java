package tn.finrisk.alertcaseservice.web.dto;

import jakarta.validation.constraints.NotNull;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;

/** Charge utile de PATCH /api/alerts/{id}/status. */
public record AlertStatusRequest(
        @NotNull(message = "status est obligatoire")
        AlertStatus status) {
}
