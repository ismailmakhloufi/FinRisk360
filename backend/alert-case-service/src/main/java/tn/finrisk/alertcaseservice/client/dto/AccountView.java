package tn.finrisk.alertcaseservice.client.dto;

/**
 * Sous-ensemble du compte tel que renvoye par GET /api/accounts/{id}
 * (account-service, port 8082). Sert uniquement a rattacher un groupe
 * d'alertes au CLIENT emetteur, pour que l'analyste voie « X alertes
 * apparentées du client Y ».
 */
public record AccountView(
        Long accountId,
        Long customerId,
        String accountNumber,
        String currency) {
}
