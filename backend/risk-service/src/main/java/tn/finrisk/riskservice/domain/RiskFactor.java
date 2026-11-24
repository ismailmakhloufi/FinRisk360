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
import tn.finrisk.riskservice.domain.enums.RiskCategory;

/**
 * Entite mappee sur "risk_factors" : POURQUOI le score est ce qu'il est.
 * Un ligne par regle declenchee + une ligne d'historique des features : c'est
 * la couche "explicable" qui fait toute la difference en soutenance (et le carburant
 * de M4 quand l'analyste lira le dossier).
 *
 * assessment_id : FK vers NOTRE table risk_assessments => stocke en Long, comme partout.
 * rule_id       : FK vers risk_rules, NULLABLE (ON DELETE SET NULL si la regle disparait).
 * factor_type   : meme domaine que risk_rules.risk_category => on REUTILISE RiskCategory
 *                 (une seule enum = impossible de les faire diverger).
 */
@Entity
@Table(name = "risk_factors")
public class RiskFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "factor_id")
    private Long factorId;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_type", nullable = false, length = 20)
    private RiskCategory factorType;

    @Column(name = "factor_score", precision = 5, scale = 2)
    private BigDecimal factorScore;

    @Column(name = "weight", precision = 4, scale = 2)
    private BigDecimal weight;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public RiskFactor() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getFactorId() {
        return factorId;
    }

    public void setFactorId(Long factorId) {
        this.factorId = factorId;
    }

    public Long getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(Long assessmentId) {
        this.assessmentId = assessmentId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public RiskCategory getFactorType() {
        return factorType;
    }

    public void setFactorType(RiskCategory factorType) {
        this.factorType = factorType;
    }

    public BigDecimal getFactorScore() {
        return factorScore;
    }

    public void setFactorScore(BigDecimal factorScore) {
        this.factorScore = factorScore;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
