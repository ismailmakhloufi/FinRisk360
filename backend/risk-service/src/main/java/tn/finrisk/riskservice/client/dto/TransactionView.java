package tn.finrisk.riskservice.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sous-ensemble de la transaction tel que renvoye par GET /api/transactions/{id}
 * (transaction-service). On ne declare que ce dont le moteur a besoin.
 * Les enums distantes (type, statut, canal) sont lues en String : ce service
 * n'a pas a connaitre les types Java d'un autre service.
 */
public record TransactionView(
        Long transactionId,
        Long accountId,
        Long merchantId,
        BigDecimal amount,
        String currency,
        String country,
        OffsetDateTime transactionDate,
        String transactionType,
        String status,
        String channel) {
}
