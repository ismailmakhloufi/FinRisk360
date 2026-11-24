package tn.finrisk.riskservice.domain;

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
import tn.finrisk.riskservice.domain.enums.Decision;
import tn.finrisk.riskservice.domain.enums.RiskLevel;

/**
 * Entite mappee sur "risk_assessments" : le VERDICT du moteur pour une transaction.
 * Table en ecriture SEULE (append-only) : chaque evaluation produit une nouvelle ligne,
 * l'historique est la preuve du raisonnement (auditable, requis en soutenance).
 *
 * transaction_id : FK vers "transactions" (table du transaction-service) => stocke en Long,
 *   aucun JOIN (regle M0 §2).
 * risk_score : 0..100 (check ck_ra_score).
 */
@Entity
@Table(name = "risk_assessments")
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assessment_id")
    private Long assessmentId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20)
    private Decision decision;

    @Column(name = "assessed_at", nullable = false)
    private OffsetDateTime assessedAt;

    @Column(name = "engine_version", length = 20)
    private String engineVersion;

    public RiskAssessment() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (assessedAt == null) {
            assessedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(Long assessmentId) {
        this.assessmentId = assessmentId;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(BigDecimal riskScore) {
        this.riskScore = riskScore;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public OffsetDateTime getAssessedAt() {
        return assessedAt;
    }

    public void setAssessedAt(OffsetDateTime assessedAt) {
        this.assessedAt = assessedAt;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(String engineVersion) {
        this.engineVersion = engineVersion;
    }
}
