package tn.finrisk.accountservice.web.error;

/** Compte demande qui n'existe pas -> 404. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("Compte introuvable : id = " + id);
    }

    public AccountNotFoundException(String accountNumber) {
        super("Compte introuvable : numero = " + accountNumber);
    }
}
