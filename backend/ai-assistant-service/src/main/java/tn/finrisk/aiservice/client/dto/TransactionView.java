package tn.finrisk.aiservice.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Transaction lue CHEZ transaction-service (GET /api/transactions/{id}).
 * Le carburant de l'analyse IA : montant, date, canal, IP, device,
 * bénéficiaire — les champs sur lesquels les signaux se calculent.
 * Enums distantes en String (règle M0).
 */
public record TransactionView(
        Long transactionId,
        Long accountId,
        Long merchantId,
        String merchantName,
        BigDecimal amount,
        String currency,
        String transactionType,
        String country,
        OffsetDateTime transactionDate,
        String channel,
        String ipAddress,
        String deviceId,
        String beneficiaryName,
        Long beneficiaryAccountId,
        String status,
        OffsetDateTime createdAt) {

    /** Signature d'avant merchantName + beneficiaryAccountId. On la garde
     *  pour que les fixtures (tests, payloads partiels) restent lisibles. */
    public TransactionView(Long transactionId, Long accountId, Long merchantId,
            BigDecimal amount, String currency, String transactionType, String country,
            OffsetDateTime transactionDate, String channel, String ipAddress, String deviceId,
            String beneficiaryName, Long beneficiaryAccountId,
            String status, OffsetDateTime createdAt) {
        this(transactionId, accountId, merchantId, null, amount, currency, transactionType,
                country, transactionDate, channel, ipAddress, deviceId, beneficiaryName,
                beneficiaryAccountId, status, createdAt);
    }

    /** Signature d'avant l'ajout de beneficiaryAccountId. */
    public TransactionView(Long transactionId, Long accountId, Long merchantId,
            BigDecimal amount, String currency, String transactionType, String country,
            OffsetDateTime transactionDate, String channel, String ipAddress, String deviceId,
            String beneficiaryName, String status, OffsetDateTime createdAt) {
        this(transactionId, accountId, merchantId, null, amount, currency, transactionType,
                country, transactionDate, channel, ipAddress, deviceId, beneficiaryName, null,
                status, createdAt);
    }
}
