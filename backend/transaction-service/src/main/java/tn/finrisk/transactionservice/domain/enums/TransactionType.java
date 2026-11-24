package tn.finrisk.transactionservice.domain.enums;

/** Type de transaction - CHECK IN transactions.transaction_type (02-schema.sql). */
public enum TransactionType {
    PAYMENT,
    TRANSFER,
    WITHDRAWAL,
    DEPOSIT
}
