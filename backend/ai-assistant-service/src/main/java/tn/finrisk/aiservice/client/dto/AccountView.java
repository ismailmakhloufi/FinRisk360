package tn.finrisk.aiservice.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Compte lu CHEZ customer-service (GET /api/customers/{id}/accounts) :
 * sert de pont alerte -> transactions (par accountId) pour l'historique.
 * Enums distantes en String.
 */
public record AccountView(
        Long accountId,
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal balance,
        LocalDate openingDate,
        String status) {
}
