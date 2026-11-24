package tn.finrisk.accountservice.web.error;

/** Solde insuffisant pour un DEBIT -> 409. */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String accountNumber) {
        super("Solde insuffisant sur le compte " + accountNumber);
    }
}
