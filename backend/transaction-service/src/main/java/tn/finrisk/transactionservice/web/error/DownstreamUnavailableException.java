package tn.finrisk.transactionservice.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Un service distant (account-service) est injoignable ou a renvoye une
 * erreur serveur pendant la REGULATION. Le statut COMPLETED n'est PAS
 * enregistre (rollback) : pas de transaction validee sans mouvement de solde.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String service, Throwable cause) {
        super("Service distant indisponible : " + service, cause);
    }
}
