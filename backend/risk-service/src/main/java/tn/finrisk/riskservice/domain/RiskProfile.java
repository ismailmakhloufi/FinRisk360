package tn.finrisk.riskservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tn.finrisk.riskservice.domain.enums.RiskLevel;

/**
 * Entite mappee sur "risk_profiles" : la BASELINE du client sur les 90 derniers jours
 * (moyennes, P95, pays/horaire habituels...). C'est l'entree du Feature Engine :
 * sans profil, une transaction a 400 TND est "normale" ; avec le profil d'un client
 * qui depense toujours 40 TND, elle est atypique.
 *
 * customer_id : FK vers "customers" (table du customer-service) => stocke en Long,
 *   aucun JOIN (regle M0 §2), l'integrite est garantie par PostgreSQL.
 *   UNIQUE : un seul profil par client (uq_risk_profiles_customer).
 *
 * La colonne feature_snapshot (JSONB) n'est PAS mappee : les details des features
 * sont deja portes de maniere lisible par risk_factors.reason (le JSON revient
 * en M6 pour l'IA).
 */
@Entity
@Table(name = "risk_profiles")
public class RiskProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Column(name = "avg_transaction_amount", precision = 15, scale = 2)
    private BigDecimal avgTransactionAmount;

    @Column(name = "median_transaction_amount", precision = 15, scale = 2)
    private BigDecimal medianTransactionAmount;

    @Column(name = "max_transaction_amount", precision = 15, scale = 2)
    private BigDecimal maxTransactionAmount;

    @Column(name = "p95_amount", precision = 15, scale = 2)
    private BigDecimal p95Amount;

    @Column(name = "p99_amount", precision = 15, scale = 2)
    private BigDecimal p99Amount;

    @Column(name = "transactions_per_day", precision = 5, scale = 2)
    private BigDecimal transactionsPerDay;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "usual_country", length = 2)
    private String usualCountry;

    // SMALLINT en SQL (check 0..23) : le type JDBC doit etre force, sinon
    // validate attend "integer" (Java Integer) et refuse "int2".
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "usual_transaction_hour")
    private Integer usualTransactionHour;

    @Column(name = "usual_merchant_count")
    private Integer usualMerchantCount;

    @Column(name = "stats_window_days", nullable = false)
    private Integer statsWindowDays = 90;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_risk_level", length = 20)
    private RiskLevel profileRiskLevel = RiskLevel.LOW;

    /** Snapshot JSONB des features de la derniere evaluation (explicabilite / M6 IA). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_snapshot")
    private String featureSnapshot;

    @Column(name = "last_updated", nullable = false)
    private OffsetDateTime lastUpdated;

    public RiskProfile() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (statsWindowDays == null) {
            statsWindowDays = 90;
        }
        if (profileRiskLevel == null) {
            profileRiskLevel = RiskLevel.LOW;
        }
        if (lastUpdated == null) {
            lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @PreUpdate
    void preUpdate() {
        lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getAvgTransactionAmount() {
        return avgTransactionAmount;
    }

    public void setAvgTransactionAmount(BigDecimal avgTransactionAmount) {
        this.avgTransactionAmount = avgTransactionAmount;
    }

    public BigDecimal getMedianTransactionAmount() {
        return medianTransactionAmount;
    }

    public void setMedianTransactionAmount(BigDecimal medianTransactionAmount) {
        this.medianTransactionAmount = medianTransactionAmount;
    }

    public BigDecimal getMaxTransactionAmount() {
        return maxTransactionAmount;
    }

    public void setMaxTransactionAmount(BigDecimal maxTransactionAmount) {
        this.maxTransactionAmount = maxTransactionAmount;
    }

    public BigDecimal getP95Amount() {
        return p95Amount;
    }

    public void setP95Amount(BigDecimal p95Amount) {
        this.p95Amount = p95Amount;
    }

    public BigDecimal getP99Amount() {
        return p99Amount;
    }

    public void setP99Amount(BigDecimal p99Amount) {
        this.p99Amount = p99Amount;
    }

    public BigDecimal getTransactionsPerDay() {
        return transactionsPerDay;
    }

    public void setTransactionsPerDay(BigDecimal transactionsPerDay) {
        this.transactionsPerDay = transactionsPerDay;
    }

    public String getUsualCountry() {
        return usualCountry;
    }

    public void setUsualCountry(String usualCountry) {
        this.usualCountry = usualCountry;
    }

    public Integer getUsualTransactionHour() {
        return usualTransactionHour;
    }

    public void setUsualTransactionHour(Integer usualTransactionHour) {
        this.usualTransactionHour = usualTransactionHour;
    }

    public Integer getUsualMerchantCount() {
        return usualMerchantCount;
    }

    public void setUsualMerchantCount(Integer usualMerchantCount) {
        this.usualMerchantCount = usualMerchantCount;
    }

    public Integer getStatsWindowDays() {
        return statsWindowDays;
    }

    public void setStatsWindowDays(Integer statsWindowDays) {
        this.statsWindowDays = statsWindowDays;
    }

    public RiskLevel getProfileRiskLevel() {
        return profileRiskLevel;
    }

    public void setProfileRiskLevel(RiskLevel profileRiskLevel) {
        this.profileRiskLevel = profileRiskLevel;
    }

    public String getFeatureSnapshot() {
        return featureSnapshot;
    }

    public void setFeatureSnapshot(String featureSnapshot) {
        this.featureSnapshot = featureSnapshot;
    }

    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(OffsetDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
