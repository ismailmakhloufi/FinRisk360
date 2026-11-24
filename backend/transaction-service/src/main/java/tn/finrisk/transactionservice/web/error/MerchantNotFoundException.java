package tn.finrisk.transactionservice.web.error;

/** Marchand demande qui n'existe pas -> 404. */
public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(Long id) {
        super("Marchand introuvable : id = " + id);
    }
}
