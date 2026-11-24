package tn.finrisk.customerservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.domain.enums.CustomerType;

/**
 * Entite mappee sur la table SQL "customers" (postgres/02-schema.sql).
 * Chaque annotation correspond a une colonne reelle : Hibernate en mode
 * "validate" refusera de demarrer si les deux divergent.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "email", length = 100, unique = true)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "city", length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 20)
    private CustomerType customerType = CustomerType.RETAIL;

    @Column(name = "registration_date", nullable = false)
    private OffsetDateTime registrationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    public Customer() {
    }

    /** Equivalent du DEFAULT de la colonne SQL (cote Java). */
    @PrePersist
    void prePersist() {
        if (registrationDate == null) {
            registrationDate = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
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

    public CustomerType getCustomerType() {
        return customerType;
    }

    public void setCustomerType(CustomerType customerType) {
        this.customerType = customerType;
    }

    public OffsetDateTime getRegistrationDate() {
        return registrationDate;
    }

    public void setRegistrationDate(OffsetDateTime registrationDate) {
        this.registrationDate = registrationDate;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public void setStatus(CustomerStatus status) {
        this.status = status;
    }
}
