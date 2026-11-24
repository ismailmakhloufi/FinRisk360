package tn.finrisk.authservice.web;

import tn.finrisk.authservice.security.JwtException;

/**
 * Lecture du header Authorization : « Bearer <jwt> ».
 * Header absent ou mal forme -> JwtException -> 401 (meme code que token
 * invalide : on n'apprend rien a l'interrogateur sur ce qui manque).
 */
public final class Bearer {

    private Bearer() {
    }

    public static String parse(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new JwtException("Token manquant (header Authorization : Bearer <token>)");
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            throw new JwtException("Format de token invalide (attendu : Bearer <token>)");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new JwtException("Token manquant (header Authorization : Bearer <token>)");
        }
        return token;
    }
}
