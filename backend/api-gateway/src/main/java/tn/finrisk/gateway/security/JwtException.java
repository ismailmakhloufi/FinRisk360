package tn.finrisk.gateway.security;

/** Token absent, malforme, forge, expire ou emetteur inconnu -> 401. */
public class JwtException extends RuntimeException {

    public JwtException(String message) {
        super(message);
    }
}
