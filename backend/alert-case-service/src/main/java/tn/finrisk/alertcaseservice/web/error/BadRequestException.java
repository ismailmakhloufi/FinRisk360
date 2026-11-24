package tn.finrisk.alertcaseservice.web.error;

/** Requete metier valide techniquement mais incomplete (HTTP 400),
 *  ex. commentaire sans X-User-Id (user_id NOT NULL en base). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
