package tn.finrisk.riskservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import tn.finrisk.riskservice.domain.enums.ComparisonOperator;
import tn.finrisk.riskservice.domain.enums.ComparisonOperatorConverter;
import tn.finrisk.riskservice.domain.enums.RiskCategory;
import tn.finrisk.riskservice.domain.enums.Severity;

/**
 * Entite mappee sur "risk_rules" : les regles du moteur, CONFIGURABLES sans redemarrer
 * (seeds RULE-001..006 dans 02-schema.sql). Le poids est la part maximale du score
 * (0..1) que la regle peut apporter ; enabled=false = regle ignoree par le moteur.
 *
 * comparison_operator : stocke le symbole SQL (">", "BETWEEN"...) via le converter.
 */
@Entity
@Table(name = "risk_rules")
public class RiskRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_code", nullable = false, unique = true, length = 20)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_category", nullable = false, length = 20)
    private RiskCategory riskCategory;

    @Convert(converter = ComparisonOperatorConverter.class)
    @Column(name = "comparison_operator", length = 10)
    private ComparisonOperator comparisonOperator;

    @Column(name = "threshold_value", precision = 10, scale = 2)
    private BigDecimal thresholdValue;

    @Column(name = "weight", nullable = false, precision = 4, scale = 2)
    private BigDecimal weight = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity = Severity.MEDIUM;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public RiskRule() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (weight == null) {
            weight = BigDecimal.ZERO;
        }
        if (severity == null) {
            severity = Severity.MEDIUM;
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public RiskCategory getRiskCategory() {
        return riskCategory;
    }

    public void setRiskCategory(RiskCategory riskCategory) {
        this.riskCategory = riskCategory;
    }

    public ComparisonOperator getComparisonOperator() {
        return comparisonOperator;
    }

    public void setComparisonOperator(ComparisonOperator comparisonOperator) {
        this.comparisonOperator = comparisonOperator;
    }

    public BigDecimal getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(BigDecimal thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
