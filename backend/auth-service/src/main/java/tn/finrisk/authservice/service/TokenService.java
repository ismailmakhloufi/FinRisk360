package tn.finrisk.authservice.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.security.JwtException;
import tn.finrisk.authservice.security.JwtSupport;

/**
 * Emission et controle des JWT.
 *
 * Deux types de token (claim "typ") :
 *  - access  : court (15 min par defaut), porte le role, sert a appeler les
 *              API ; c'est celui que le gateway verifie ;
 *  - refresh : long (7 jours), NE porte PAS de droits, ne sert qu'a obtenir
 *              un nouvel access sans remettre le mot de passe.
 * Verifier qu'un access n'est pas un refresh (et inversement) empeche qu'un
 * refresh "fuite" soit rejoue comme access — meme si la signature est bonne.
 */
@Service
public class TokenService {

    private final JwtSupport jwt;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public TokenService(JwtSupport jwt,
                        @Value("${auth.jwt.access-ttl-minutes}") long accessTtlMinutes,
                        @Value("${auth.jwt.refresh-ttl-days}") long refreshTtlDays) {
        this.jwt = jwt;
        this.accessTtlSeconds = accessTtlMinutes * 60;
        this.refreshTtlSeconds = refreshTtlDays * 24 * 60 * 60;
    }

    /** Le contrat de claims partage avec api-gateway. */
    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
    }

    public TokenPair issuePair(AppUser user) {
        String access = jwt.sign(claims(user, "access"), accessTtlSeconds);
        String refresh = jwt.sign(claims(user, "refresh"), refreshTtlSeconds);
        return new TokenPair(access, refresh, accessTtlSeconds);
    }

    /** Verifie signature + expiration + emetteur + que c'est bien un access. */
    public JsonNode verifyAccess(String token) {
        JsonNode claims = jwt.verify(token);
        requireType(claims, "access");
        return claims;
    }

    /** Idem pour le refresh. */
    public JsonNode verifyRefresh(String token) {
        JsonNode claims = jwt.verify(token);
        requireType(claims, "refresh");
        return claims;
    }

    private void requireType(JsonNode claims, String attendu) {
        if (!attendu.equals(claims.path("typ").asText())) {
            throw new JwtException("Type de token incorrect (attendu : " + attendu + ")");
        }
    }

    private Map<String, Object> claims(AppUser user, String type) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.getUserId());
        claims.put("usr", user.getUsername());
        claims.put("rol", user.getRole().name());
        claims.put("typ", type);
        return claims;
    }
}
