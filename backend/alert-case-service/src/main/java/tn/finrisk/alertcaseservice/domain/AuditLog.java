package tn.finrisk.alertcaseservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Entite mappee sur "audit_logs" : la TRACE exigee par le jalon M4
 * (« alerte -> case -> commentaire -> cloture tracee »).
 *
 * AMENDEMENT M4 (comme risk_profiles en M3) : cette table est listee chez
 * auth-service dans architecture-services.md §2, mais le critere M4 exige
 * des des que maintenant que chaque mutation d'alerte/case laisse une
 * empreinte. alert-case-service l'ecrit pour SES actions (ALERT, CASE,
 * CASE_COMMENT) ; la reconciliation avec auth-service (events AUTH) se
 * fera en M5 — un seul ecrivain par domaine reste la regle M0.
 *
 * old_value / new_value : pour un changement d'etat, l'avant/apres brut
 *   ("OPEN" -> "ACKNOWLEDGED") ; pour une creation, un resume de l'entite.
 * ip_address : depuis M5 via header X-Forwarded-For / X-User-Id (nullable en M4).
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    public AuditLog() {
    }

    /** Equivalent du DEFAULT now() de la colonne SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getAuditId() {
        return auditId;
    }

    public void setAuditId(Long auditId) {
        this.auditId = auditId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}
