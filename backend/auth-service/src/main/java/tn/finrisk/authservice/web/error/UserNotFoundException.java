package tn.finrisk.authservice.web.error;

/** Utilisateur inconnu -> 404. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long userId) {
        super("Utilisateur " + userId + " introuvable");
    }
}
