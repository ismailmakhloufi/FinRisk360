package tn.finrisk.alertcaseservice.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sous-ensemble de la transaction tel que renvoye par GET /api/transactions/{id}
 * (transaction-service, port 8083). On ne declare que ce dont l'AGREGATION a
 * besoin : le compte emetteur (perimetre de regroupement) et le montant
 * (cumul du groupe). Les enums distantes sont lues en String (regle M0).
 */
public record TransactionView(
        Long transactionId,
        Long accountId,
        BigDecimal amount,
        String currency,
        String transactionType,
        OffsetDateTime transactionDate,
        String beneficiaryName,
        Long beneficiaryAccountId,
        String status) {
}
