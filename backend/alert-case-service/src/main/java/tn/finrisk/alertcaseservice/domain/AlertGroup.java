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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tn.finrisk.alertcaseservice.domain.enums.GroupScope;
import tn.finrisk.alertcaseservice.domain.enums.GroupStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;

/**
 * Entite mappee sur "alert_groups" : un AGREGAT d'alertes apparentees.
 *
 * Pourquoi : 10 virements de 100 TND ouvrent 10 alertes LOW independantes.
 * Chacune prise isolement est anodine, mais le MOTIF (fractionnement) n'est
 * visible QUE si on les lit ensemble. Chaque alerte garde sa severite
 * INDIVIDUELLE (elle reflete SON evaluation) ; c'est le GROUPE qui porte une
 * severite AGREEGEE qui monte avec le nombre d'alertes. L'analyste voit un
 * pattern, pas du bruit.
 *
 * Fenetre GLISSANTE : window_start + window_hours. Une alerte ne rejoint un
 * groupe que si elle tombe dans sa fenetre ; passe le delai, la prochaine
 * alerte ouvre un nouveau groupe (on ne melange pas deux episodes espaces).
 *
 * Optimistic locking (@Version) : deux evaluations peuvent se terminer en
 * meme temps et tenter d'incrementer le meme groupe. Le conflit est resolu
 * par la relecture, jamais par un compteur fausse.
 */
@Entity
@Table(name = "alert_groups")
public class AlertGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_id")
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private GroupScope scopeType;

    /** accountId / customerId / cle beneficiaire — texte pour rester extensible. */
    @Column(name = "scope_value", nullable = false, length = 60)
    private String scopeValue;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "window_start", nullable = false)
    private OffsetDateTime windowStart;

    @Column(name = "window_hours", nullable = false)
    private int windowHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GroupStatus status = GroupStatus.OPEN;

    @Column(name = "related_count", nullable = false)
    private int relatedCount;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    public AlertGroup() {
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = GroupStatus.OPEN;
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
    }

    /** Fin de la fenetre glissante (exclusive) : au-dela, plus d'alerte ne rejoint. */
    public OffsetDateTime windowEnd() {
        return windowStart.plusHours(windowHours);
    }

    /** La fenetre couvre-t-elle cet instant ? */
    public boolean covers(OffsetDateTime moment) {
        return !moment.isBefore(windowStart) && moment.isBefore(windowEnd());
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public GroupScope getScopeType() {
        return scopeType;
    }

    public void setScopeType(GroupScope scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeValue() {
        return scopeValue;
    }

    public void setScopeValue(String scopeValue) {
        this.scopeValue = scopeValue;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public OffsetDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(OffsetDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public int getWindowHours() {
        return windowHours;
    }

    public void setWindowHours(int windowHours) {
        this.windowHours = windowHours;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public GroupStatus getStatus() {
        return status;
    }

    public void setStatus(GroupStatus status) {
        this.status = status;
    }

    public int getRelatedCount() {
        return relatedCount;
    }

    public void setRelatedCount(int relatedCount) {
        this.relatedCount = relatedCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
