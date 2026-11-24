package tn.finrisk.transactionservice.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;

/** Charge utile de sortie : une transaction visible depuis l'API (REST et SOAP). */
public record TransactionResponse(
        Long transactionId,
        Long accountId,
        Long merchantId,
        String merchantName,
        BigDecimal amount,
        String currency,
        TransactionType transactionType,
        String country,
        OffsetDateTime transactionDate,
        TransactionChannel channel,
        String ipAddress,
        String deviceId,
        String beneficiaryName,
        Long beneficiaryAccountId,
        String beneficiaryRIB,
        TransactionStatus status,
        OffsetDateTime createdAt) {

    /** Signature d'avant merchantName — le nom du marchand était lisible
     *  uniquement via GET /api/merchants/{id}. On la garde pour ne pas
     *  casser les consommateurs (tests, payloads partiels). */
    public TransactionResponse(Long transactionId, Long accountId, Long merchantId,
            BigDecimal amount, String currency, TransactionType transactionType,
            String country, OffsetDateTime transactionDate, TransactionChannel channel,
            String ipAddress, String deviceId, String beneficiaryName,
            Long beneficiaryAccountId, TransactionStatus status, OffsetDateTime createdAt) {
        this(transactionId, accountId, merchantId, null, amount, currency, transactionType,
                country, transactionDate, channel, ipAddress, deviceId, beneficiaryName,
                beneficiaryAccountId, null, status, createdAt);
    }
}
