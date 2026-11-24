package tn.finrisk.transactionservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;

/**
 * Entite mappee sur la table "transactions" (02-schema.sql) - le coeur du projet.
 *
 * account_id : FK vers "accounts" (table du account-service) => stocke en Long,
 *   aucun JOIN (regle M0 §2), l'integrite est garantie par PostgreSQL.
 * merchant_id : FK vers "merchants" (NOTRE table) => relation @ManyToOne possible.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "TND";

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "transaction_date", nullable = false)
    private OffsetDateTime transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    private TransactionChannel channel;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "device_id", length = 50)
    private String deviceId;

    @Column(name = "beneficiary_name", length = 100)
    private String beneficiaryName;

    /**
     * Compte INTERNE a crediter lors de la regulation (migration 05).
     * Null = destinataire externe (autre banque) : seul le debit du compte
     * source est applique. Trouve par RIB cote simulateur — jamais par nom,
     * un nom se tape avec des fautes d'orthographe.
     */
    @Column(name = "beneficiary_account_id")
    private Long beneficiaryAccountId;

    /**
     * RIB TN canonique du beneficiaire, valide (20 chiffres + cle modulo 97)
     * et resolu cote API a la creation (migration 09). Null = pas de RIB
     * fourni (paiement marchand, flux de demonstration).
     */
    @Column(name = "beneficiary_rib", length = 26)
    private String beneficiaryRIB;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Transaction() {
    }

    /** Equivalent des DEFAULT de la colonne SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (transactionDate == null) {
            transactionDate = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (currency == null) {
            currency = "TND";
        }
        if (status == null) {
            status = TransactionStatus.PENDING;
        }
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public void setMerchant(Merchant merchant) {
        this.merchant = merchant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public OffsetDateTime getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(OffsetDateTime transactionDate) {
        this.transactionDate = transactionDate;
    }

    public TransactionChannel getChannel() {
        return channel;
    }

    public void setChannel(TransactionChannel channel) {
        this.channel = channel;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public void setBeneficiaryName(String beneficiaryName) {
        this.beneficiaryName = beneficiaryName;
    }

    public Long getBeneficiaryAccountId() {
        return beneficiaryAccountId;
    }

    public void setBeneficiaryAccountId(Long beneficiaryAccountId) {
        this.beneficiaryAccountId = beneficiaryAccountId;
    }

    public String getBeneficiaryRIB() {
        return beneficiaryRIB;
    }

    public void setBeneficiaryRIB(String beneficiaryRIB) {
        this.beneficiaryRIB = beneficiaryRIB;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
