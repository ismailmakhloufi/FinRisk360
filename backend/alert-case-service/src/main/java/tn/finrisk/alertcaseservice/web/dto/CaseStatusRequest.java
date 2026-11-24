package tn.finrisk.alertcaseservice.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;

/**
 * Charge utile de PATCH /api/cases/{id}/status.
 * investigationResult optionnel (max 50, contrainte SQL) : arbitrage de
 * l'analyste pose au moment du changement d'etat.
 */
public record CaseStatusRequest(
        @NotNull(message = "status est obligatoire")
        CaseStatus status,
        @Size(max = 50, message = "investigationResult : 50 caracteres maximum")
        String investigationResult) {
}
