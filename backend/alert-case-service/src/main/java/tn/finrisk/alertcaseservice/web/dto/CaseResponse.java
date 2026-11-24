package tn.finrisk.alertcaseservice.web.dto;

import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.enums.CasePriority;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;
import java.time.OffsetDateTime;

/** Reponse rendue pour une case (jamais l'entite brute). */
public record CaseResponse(
        Long caseId,
        Long alertId,
        Long assignedTo,
        CaseStatus status,
        CasePriority priority,
        String investigationResult,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt) {

    public static CaseResponse from(Case entity) {
        return new CaseResponse(
                entity.getCaseId(),
                entity.getAlertId(),
                entity.getAssignedTo(),
                entity.getStatus(),
                entity.getPriority(),
                entity.getInvestigationResult(),
                entity.getOpenedAt(),
                entity.getClosedAt());
    }
}
