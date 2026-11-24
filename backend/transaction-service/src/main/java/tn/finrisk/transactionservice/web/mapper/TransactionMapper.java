package tn.finrisk.transactionservice.web.mapper;

import org.springframework.stereotype.Component;
import tn.finrisk.transactionservice.domain.Merchant;
import tn.finrisk.transactionservice.domain.Transaction;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;

/** Traduction entite <-> DTO des transactions. */
@Component
public class TransactionMapper {

    public Transaction toEntity(TransactionCreateRequest request) {
        Transaction transaction = new Transaction();
        transaction.setAccountId(request.accountId());
        transaction.setAmount(request.amount());
        if (request.currency() != null && !request.currency().isBlank()) {
            transaction.setCurrency(request.currency().toUpperCase());
        }
        transaction.setTransactionType(request.transactionType());
        transaction.setCountry(request.country().toUpperCase());
        transaction.setChannel(request.channel());
        transaction.setIpAddress(request.ipAddress());
        transaction.setDeviceId(request.deviceId());
        transaction.setBeneficiaryName(request.beneficiaryName());
        transaction.setBeneficiaryAccountId(request.beneficiaryAccountId());
        return transaction;
    }

    public TransactionResponse toResponse(Transaction transaction) {
        Merchant merchant = transaction.getMerchant();
        return new TransactionResponse(
                transaction.getTransactionId(),
                transaction.getAccountId(),
                merchant == null ? null : merchant.getMerchantId(),
                merchant == null ? null : merchant.getMerchantName(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getTransactionType(),
                transaction.getCountry(),
                transaction.getTransactionDate(),
                transaction.getChannel(),
                transaction.getIpAddress(),
                transaction.getDeviceId(),
                transaction.getBeneficiaryName(),
                transaction.getBeneficiaryAccountId(),
                transaction.getBeneficiaryRIB(),
                transaction.getStatus(),
                transaction.getCreatedAt());
    }
}
