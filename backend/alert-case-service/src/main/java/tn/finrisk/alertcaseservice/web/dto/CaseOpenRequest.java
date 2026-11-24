package tn.finrisk.alertcaseservice.web.dto;

import tn.finrisk.alertcaseservice.domain.enums.CasePriority;

/**
 * Charge utile de POST /api/alerts/{alertId}/cases.
 * priority optionnelle (defaut MEDIUM cote entite) ; assignedTo optionnel
 * (defaut : le porteur du header X-User-Id, voir CaseService.open).
 */
public record CaseOpenRequest(
        CasePriority priority,
        Long assignedTo) {
}
