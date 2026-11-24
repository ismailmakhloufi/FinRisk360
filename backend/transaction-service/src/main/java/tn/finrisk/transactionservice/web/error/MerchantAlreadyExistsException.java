package tn.finrisk.transactionservice.web.error;

/** Nom de marchand deja utilise -> 409. */
public class MerchantAlreadyExistsException extends RuntimeException {

    public MerchantAlreadyExistsException(String merchantName) {
        super("Ce marchand existe deja : " + merchantName);
    }
}
