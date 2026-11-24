package tn.finrisk.transactionservice.web.dto;

import java.time.OffsetDateTime;
import tn.finrisk.transactionservice.domain.enums.MerchantStatus;

/** Charge utile de sortie : un marchand visible depuis l'API. */
public record MerchantResponse(
        Long merchantId,
        String merchantName,
        String merchantCategory,
        String country,
        String city,
        MerchantStatus status,
        OffsetDateTime createdAt) {
}
