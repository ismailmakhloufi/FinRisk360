package tn.finrisk.authservice.web.error;

/** Conflit metier ou d'unicite -> 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
