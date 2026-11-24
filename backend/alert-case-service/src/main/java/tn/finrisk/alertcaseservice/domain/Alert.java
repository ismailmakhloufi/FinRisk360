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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;

/**
 * Entite mappee sur "alerts" : l'incarnation du critere M4 « une evaluation
 * risque devient une alerte ». Toute mutation est tracee dans audit_logs.
 *
 * assessment_id : FK vers risk_assessments (table du risk-service) => stockee
 *   en Long, la lecture de l'evaluation se fait PAR API (regle M0 §2).
 *   La FK PostgreSQL (ON DELETE CASCADE) reste la garantie d'integrite.
 * alert_code    : "ALT-{assessmentId}" — une seule alerte par evaluation
 *   (regle service, doublon -> 409) ; l'unicite SQL est le filet de securite.
 * assigned_to   : FK users (auth-service, M5) => Long, jamais de SELECT users :
 *   c'est PostgreSQL qui verifie, l'erreur FK est traduite en 404 propre.
 */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "alert_code", nullable = false, unique = true, length = 30)
    private String alertCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 20)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(name = "assigned_to")
    private Long assignedTo;

    /**
     * Groupe d'alertes apparentees auquel cette alerte appartient (NULL si
     * l'agregation n'a pas pu se faire — transaction-service hors ligne par
     * exemple). La severite de l'alerte N'EN DEPEND PAS : elle reflete son
     * evaluation propre ; c'est le groupe qui porte l'escalade.
     */
    @Column(name = "group_id")
    private Long groupId;

    /**
     * Montant de la transaction, copie a la creation. Sans elle, afficher le
     * cumul d'un groupe exigerait de re-interroger transaction-service a chaque
     * affichage — et le groupe resterait vide si ce service est arrete.
     */
    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    public Alert() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (status == null) {
            status = AlertStatus.OPEN;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public Long getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(Long assessmentId) {
        this.assessmentId = assessmentId;
    }

    public String getAlertCode() {
        return alertCode;
    }

    public void setAlertCode(String alertCode) {
        this.alertCode = alertCode;
    }

    public AlertType getAlertType() {
        return alertType;
    }

    public void setAlertType(AlertType alertType) {
        this.alertType = alertType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    public Long getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(Long assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
