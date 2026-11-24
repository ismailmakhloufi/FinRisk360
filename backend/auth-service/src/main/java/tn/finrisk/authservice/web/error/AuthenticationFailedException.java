package tn.finrisk.authservice.web.error;

/** Login refuse (mot de passe faux, compte inconnu, token invalide) -> 401.
 *  Message UNIQUE « Identifiants invalides » : on ne dit jamais au client
 *  si le username existe (anti-enumeration de comptes). */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
