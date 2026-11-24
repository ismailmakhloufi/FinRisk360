package tn.finrisk.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** La copie conforme du JWT doit se comporter COMME celle d'auth-service. */
class GatewayJwtSupportTest {

    private final JwtSupport jwt =
            new JwtSupport("test-secret-hmac-sha256-minimum-32-chars-2026", "finrisk360");

    @Test
    void signVerify_roundTrip() {
        String token = jwt.sign(Map.of("sub", 1L, "rol", "ADMIN", "typ", "access"), 300);

        JsonNode claims = jwt.verify(token);

        assertThat(claims.path("sub").asLong()).isEqualTo(1L);
        assertThat(claims.path("rol").asText()).isEqualTo("ADMIN");
        assertThat(claims.path("iss").asText()).isEqualTo("finrisk360");
    }

    @Test
    void payloadTouche_rejete() {
        String token = jwt.sign(Map.of("sub", 1L, "rol", "ANALYST"), 300);
        String[] parts = token.split("\\.");

        assertThatThrownBy(() -> jwt.verify(parts[0] + "." + parts[1] + "." + "AAAA"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expire_rejete() {
        assertThatThrownBy(() -> jwt.verify(jwt.sign(Map.of("sub", 1L), -5)))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token expire");
    }

    @Test
    void expirationInvalide_rejetee() {
        assertThatThrownBy(() -> jwt.verify(jwt.sign(Map.of("sub", 1L, "exp", 0), 300)))
                .isInstanceOf(JwtException.class)
                .hasMessage("Expiration manquante ou invalide");
    }

    @Test
    void productionSecret_faibleOuDemo_rejete() {
        assertThatThrownBy(() -> new ProductionJwtSecretCheck("court"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionJwtSecretCheck(
                "finrisk360-demo-jwt-secret-hmac-sha256-min-32-chars-2026"))
                .isInstanceOf(IllegalStateException.class);
        new ProductionJwtSecretCheck("une-cle-de-test-de-plus-de-32-octets-aleatoires");
    }

    @Test
    void algorithmeNone_rejete() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":1,\"iss\":\"finrisk360\",\"exp\":9999999999}".getBytes());

        assertThatThrownBy(() -> jwt.verify(header + "." + payload + "."))
                .isInstanceOf(JwtException.class)
                .hasMessage("Algorithme non permis");
    }

    @Test
    void secretDifferent_neVerifiePas() {
        JwtSupport autre = new JwtSupport("un-autre-secret-completement-different-2026!", "finrisk360");

        assertThatThrownBy(() -> jwt.verify(autre.sign(Map.of("sub", 1L), 300)))
                .isInstanceOf(JwtException.class)
                .hasMessage("Signature invalide");
    }
}
