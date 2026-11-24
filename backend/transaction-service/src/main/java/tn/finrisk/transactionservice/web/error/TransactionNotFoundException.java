package tn.finrisk.transactionservice.web.error;

/** Transaction demandee qui n'existe pas -> 404. */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(Long id) {
        super("Transaction introuvable : id = " + id);
    }
}
