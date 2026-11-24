package tn.finrisk.customerservice.web.error;

/** Un client porte deja cet email (contrainte UNIQUE) -> HTTP 409. */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Un client possede deja cet email : " + email);
    }
}
