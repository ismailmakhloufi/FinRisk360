package tn.finrisk.accountservice.web.error;

/**
 * Compte qui ne peut plus etre modifie (FROZEN / CLOSED) -> 409.
 * Un compte CLOSED est definitif : aucun debit, credit ni changement.
 */
public class AccountNotOperableException extends RuntimeException {

    public AccountNotOperableException(String reason) {
        super(reason);
    }
}
