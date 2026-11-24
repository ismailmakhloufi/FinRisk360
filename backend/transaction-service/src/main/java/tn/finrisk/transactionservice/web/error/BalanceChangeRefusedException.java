package tn.finrisk.transactionservice.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * account-service a REFUSE le mouvement de solde (4xx) : compte FROZEN /
 * CLOSED, solde insuffisant pour un DEBIT, compte introuvable...
 * Le statut COMPLETED n'est PAS enregistre (rollback) : le motif du refus
 * est renvoye tel quel a l'analyste.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class BalanceChangeRefusedException extends RuntimeException {

    public BalanceChangeRefusedException(String reason, Throwable cause) {
        super("Mouvement de solde refuse par account-service : " + reason, cause);
    }
}
