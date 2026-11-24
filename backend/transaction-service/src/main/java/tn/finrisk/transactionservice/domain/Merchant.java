package tn.finrisk.transactionservice.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tn.finrisk.transactionservice.domain.enums.MerchantStatus;

/**
 * Entite mappee sur la table "merchants" (02-schema.sql).
 * Table propriaire de ce service : le mapping complet (avec entites liees)
 * est donc autorise, contrairement a "accounts" qui appartient a un autre.
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "merchant_name", nullable = false, length = 100)
    private String merchantName;

    @Column(name = "merchant_category", length = 50)
    private String merchantCategory;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "city", length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MerchantStatus status = MerchantStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Merchant() {
    }

    /** Equivalent de "DEFAULT now()" cote Java. */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public void setMerchantCategory(String merchantCategory) {
        this.merchantCategory = merchantCategory;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public MerchantStatus getStatus() {
        return status;
    }

    public void setStatus(MerchantStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
