package tn.finrisk.accountservice.web.error;

/** Numero de compte deja utilise -> 409. */
public class AccountNumberAlreadyExistsException extends RuntimeException {

    public AccountNumberAlreadyExistsException(String accountNumber) {
        super("Ce numero de compte est deja utilise : " + accountNumber);
    }
}
