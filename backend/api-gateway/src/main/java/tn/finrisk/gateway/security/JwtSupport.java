package tn.finrisk.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * COPIE CONFORME de tn.finrisk.authservice.security.JwtSupport (contrat
 * partage, meme secret) : le gateway re-verifie ici le token signe par le
 * auth-service. Cette duplication DELIBEREE est le meme raisonnement que
 * pour les enums partagees — sans monorepo de communs, le contrat vit en
 * deux exemplaires ; il n'evolue que sur les deux cotes en meme temps
 * (documente dans docs/api-gateway.md).
 *
 * Les 3 segments : base64url(header) "." base64url(payload) "."
 * base64url(HMAC-SHA256(header.payload)).
 * Defenses : algorithme impose HS256 (anti "alg: none"), comparaison de
 * signature CONSTANTE (MessageDigest.isEqual), verification exp + iss.
 */
@Component
public class JwtSupport {

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final String issuer;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtSupport(@Value("${auth.jwt.secret}") String secret,
                      @Value("${auth.jwt.issuer}") String issuer) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
    }

    /** Presente pour les TESTS du gateway (on signe des tokens de test
     *  avec le meme secret) — en production, seuls les tokens signes par
     *  auth-service circulent. */
    public String sign(Map<String, Object> claims, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> all = new LinkedHashMap<>(claims);
        all.putIfAbsent("iat", now);
        all.putIfAbsent("exp", now + ttlSeconds);
        all.put("iss", issuer);
        all.putIfAbsent("jti", UUID.randomUUID().toString());
        try {
            String payloadJson = mapper.writeValueAsString(all);
            String signingInput = B64.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8))
                    + "." + B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + B64.encodeToString(hmac(signingInput));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Impossible de signer le token", e);
        }
    }

    /** Verifie signature + algorithme + emetteur + expiration. */
    public JsonNode verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new JwtException("Token malforme");
        }
        JsonNode header = parse(decodeSegment(parts[0]));
        if (!"HS256".equals(header.path("alg").asText())) {
            throw new JwtException("Algorithme non permis");
        }
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] provided;
        try {
            provided = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Token malforme");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new JwtException("Signature invalide");
        }
        JsonNode payload = parse(decodeSegment(parts[1]));
        if (!issuer.equals(payload.path("iss").asText())) {
            throw new JwtException("Emetteur inconnu");
        }
        long exp = payload.path("exp").asLong(0);
        if (exp <= 0) {
            throw new JwtException("Expiration manquante ou invalide");
        }
        if (Instant.now().getEpochSecond() >= exp) {
            throw new JwtException("Token expire");
        }
        return payload;
    }

    // --------------------------------------------------------------- OUTILS

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponible sur la JVM", e);
        }
    }

    private String decodeSegment(String segment) {
        try {
            return new String(B64D.decode(segment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Token malforme");
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (RuntimeException e) {
            throw new JwtException("Token malforme");
        }
    }
}
