package tn.finrisk.alertcaseservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tn.finrisk.alertcaseservice.domain.enums.CasePriority;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;

/**
 * Entite mappee sur "cases" : le dossier d'enquete ouvert a partir d'une alerte.
 *
 * alert_id  : UNIQUE (uq_cases_alert) => UNE case par alerte, une deuxieme
 *             ouverture -> 409. FK RESTRICT : on ne peut pas supprimer une
 *             alerte qui a un dossier (preuve d'enquete).
 * closed_at : renseigne uniquement quand status devient terminal
 *             (CLOSED ou FALSE_POSITIVE) — c'est la « cloture tracee » de M4.
 * assigned_to : FK users (M5) => Long, comme sur Alert.
 */
@Entity
@Table(name = "cases")
public class Case {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "alert_id", nullable = false, unique = true)
    private Long alertId;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CaseStatus status = CaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private CasePriority priority = CasePriority.MEDIUM;

    @Column(name = "investigation_result", length = 50)
    private String investigationResult;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    public Case() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (status == null) {
            status = CaseStatus.OPEN;
        }
        if (priority == null) {
            priority = CasePriority.MEDIUM;
        }
        if (openedAt == null) {
            openedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public Long getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(Long assignedTo) {
        this.assignedTo = assignedTo;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }

    public CasePriority getPriority() {
        return priority;
    }

    public void setPriority(CasePriority priority) {
        this.priority = priority;
    }

    public String getInvestigationResult() {
        return investigationResult;
    }

    public void setInvestigationResult(String investigationResult) {
        this.investigationResult = investigationResult;
    }

    public OffsetDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(OffsetDateTime openedAt) {
        this.openedAt = openedAt;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(OffsetDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
