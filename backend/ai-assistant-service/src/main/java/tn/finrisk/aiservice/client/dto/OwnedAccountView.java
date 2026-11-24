package tn.finrisk.aiservice.client.dto;

import java.math.BigDecimal;

/**
 * Compte lu CHEZ account-service (GET /api/accounts/{id}) : le pont
 * transaction -> customer (account.customerId), indispensable pour remonter
 * au profil avant de compiler l'historique. Enums en String (règle M0).
 */
public record OwnedAccountView(
        Long accountId,
        Long customerId,
        String accountNumber,
        BigDecimal balance,
        String status) {
}
