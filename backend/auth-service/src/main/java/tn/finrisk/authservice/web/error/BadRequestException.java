package tn.finrisk.authservice.web.error;

/** Requete syntaxiquement lisible mais metier invalide -> 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
