package tn.finrisk.accountservice.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import tn.finrisk.accountservice.domain.enums.BalanceDirection;

/**
 * Charge utile de l'operation sur le solde (PATCH /api/accounts/{id}/balance).
 * Le montant doit etre strictement positif : le sens est porte par "direction".
 */
public record BalanceOperationRequest(
        @NotNull(message = "le montant est obligatoire")
        @Positive(message = "le montant doit etre superieur a 0")
        BigDecimal amount,

        @NotNull(message = "la direction (CREDIT/DEBIT) est obligatoire")
        BalanceDirection direction) {
}
