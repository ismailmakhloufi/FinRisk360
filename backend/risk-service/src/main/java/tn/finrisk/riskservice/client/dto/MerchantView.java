package tn.finrisk.riskservice.client.dto;

import java.time.OffsetDateTime;

/**
 * Sous-ensemble du marchand tel que renvoye par GET /api/merchants/{id}
 * (transaction-service : les marchands vivent chez lui).
 * Utile pour RULE-005 "nouveau marchand" = cree depuis moins de 30 jours.
 */
public record MerchantView(
        Long merchantId,
        String merchantName,
        OffsetDateTime createdAt) {
}
