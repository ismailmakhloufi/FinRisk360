package tn.finrisk.riskservice.client.dto;

import java.math.BigDecimal;

/**
 * Sous-ensemble du compte tel que renvoye par GET /api/accounts/{id}
 * (account-service). Le seul champ vraiment necessaire au moteur : customerId,
 * car c'est la CLE du risk_profile.
 */
public record AccountView(
        Long accountId,
        Long customerId,
        String accountNumber,
        String currency,
        BigDecimal balance) {
}
