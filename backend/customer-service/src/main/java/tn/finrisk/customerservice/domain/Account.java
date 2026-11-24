package tn.finrisk.customerservice.domain;

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
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tn.finrisk.customerservice.domain.enums.AccountStatus;
import tn.finrisk.customerservice.domain.enums.AccountType;

/** Mappee sur la table SQL "accounts". */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    /** FK customers.customer_id (ON DELETE RESTRICT) - relation N:1. */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "account_number", nullable = false, length = 20, unique = true)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType = AccountType.CHECKING;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "TND";

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "opening_date", nullable = false)
    private LocalDate openingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    public Account() {
    }

    /** Equivalent de "DEFAULT CURRENT_DATE" cote Java. */
    @PrePersist
    void prePersist() {
        if (openingDate == null) {
            openingDate = LocalDate.now();
        }
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public LocalDate getOpeningDate() {
        return openingDate;
    }

    public void setOpeningDate(LocalDate openingDate) {
        this.openingDate = openingDate;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }
}
