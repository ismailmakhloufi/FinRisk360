package tn.finrisk.authservice.security;

/** Token absent, malforme, forge, expire ou emetteur inconnu -> HTTP 401
 *  (traduit par GlobalExceptionHandler). Le message est volontairement
 *  precis pour l'auditeur, jamais pour un attaquant (pas d'info sur le
 *  compte : on ne dit jamais « utilisateur inconnu » ici). */
public class JwtException extends RuntimeException {

    public JwtException(String message) {
        super(message);
    }
}
