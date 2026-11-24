package tn.finrisk.transactionservice.web.error;

/**
 * Transaction deja terminee (COMPLETED / FAILED) -> 409.
 * Un paiement effectue ou echoue ne peut plus etre modifie : c'est un fait historique.
 */
public class TransactionNotModifiableException extends RuntimeException {

    public TransactionNotModifiableException(Long id, String status) {
        super("Transaction " + id + " (" + status + ") est definitive : aucune modification possible");
    }
}
