package tn.finrisk.accountservice.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import tn.finrisk.accountservice.domain.enums.AccountStatus;
import tn.finrisk.accountservice.domain.enums.AccountType;

/** Charge utile de sortie : un compte visible depuis l'API. */
public record AccountResponse(
        Long accountId,
        Long customerId,
        String accountNumber,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        LocalDate openingDate,
        AccountStatus status) {
}
