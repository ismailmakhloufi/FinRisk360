package tn.finrisk.customerservice.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import tn.finrisk.customerservice.domain.enums.AccountStatus;
import tn.finrisk.customerservice.domain.enums.AccountType;

/** Sortie : un compte visible depuis l'API (le solde oui, l'entite non). */
public record AccountResponse(
        Long accountId,
        String accountNumber,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        LocalDate openingDate,
        AccountStatus status) {
}
