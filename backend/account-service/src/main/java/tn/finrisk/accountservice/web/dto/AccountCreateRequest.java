package tn.finrisk.accountservice.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tn.finrisk.accountservice.domain.enums.AccountType;

/**
 * Charge utile de creation (POST /api/accounts).
 * customer_id est obligatoire : un compte n'existe jamais seul.
 * accountNumber et currency sont facultatifs (generes / 'TND' par defaut).
 */
public record AccountCreateRequest(
        @NotNull(message = "l'identifiant du client est obligatoire")
        @Positive(message = "l'identifiant du client doit etre positif")
        Long customerId,

        @NotNull(message = "le type de compte est obligatoire")
        AccountType accountType,

        @Size(min = 3, max = 3, message = "la devise doit faire 3 lettres (ISO-4217)")
        String currency,

        @PositiveOrZero(message = "le solde initial ne peut pas etre negatif")
        BigDecimal openingBalance,

        @Size(max = 20, message = "le numero de compte depasse 20 caracteres")
        String accountNumber) {
}
