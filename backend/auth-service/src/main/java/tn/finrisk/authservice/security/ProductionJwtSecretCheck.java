package tn.finrisk.authservice.security;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fail closed pour eviter l'utilisation de la cle de demonstration en production. */
@Component
@Profile("!test")
public class ProductionJwtSecretCheck {
    public ProductionJwtSecretCheck(@Value("${auth.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || secret.equals("finrisk360-demo-jwt-secret-hmac-sha256-min-32-chars-2026")
                || secret.contains("${")) {
            throw new IllegalStateException("AUTH_JWT_SECRET doit etre defini et distinct du secret de demonstration (32 octets minimum)");
        }
    }
}
