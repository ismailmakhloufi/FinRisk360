package tn.finrisk.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JwtSupportTest {

    private final JwtSupport jwt =
            new JwtSupport("test-secret-hmac-sha256-minimum-32-chars-2026", "finrisk360");
    private final JwtSupport autreEmetteur =
            new JwtSupport("test-secret-hmac-sha256-minimum-32-chars-2026", "autre-projet");

    @Test
    void signEtVerify_renvoientLesClaimsIntacts() {
        String token = jwt.sign(
                Map.of("sub", 42L, "usr", "analyste", "rol", "ANALYST", "typ", "access"), 300);

        JsonNode payload = jwt.verify(token);

        assertThat(payload.path("sub").asLong()).isEqualTo(42L);
        assertThat(payload.path("usr").asText()).isEqualTo("analyste");
        assertThat(payload.path("rol").asText()).isEqualTo("ANALYST");
        assertThat(payload.path("typ").asText()).isEqualTo("access");
        assertThat(payload.path("iss").asText()).isEqualTo("finrisk360");
        assertThat(payload.path("jti").asText()).isNotBlank();
        assertThat(payload.path("exp").asLong()).isGreaterThan(payload.path("iat").asLong());
    }

    @Test
    void payloadModifie_signatureInvalide() {
        String token = jwt.sign(Map.of("sub", 1L, "rol", "ANALYST", "typ", "access"), 300);
        String[] parts = token.split("\\.");

        // On augmente le role dans le payload SANS re-signer.
        String json = "{\"sub\":1,\"rol\":\"ADMIN\",\"typ\":\"access\",\"exp\":9999999999,"
                + "\"iss\":\"finrisk360\"}";
        String fauxPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forge = parts[0] + "." + fauxPayload + "." + parts[2];

        assertThatThrownBy(() -> jwt.verify(forge))
                .isInstanceOf(JwtException.class)
                .hasMessage("Signature invalide");
    }

    @Test
    void tokenExpire_rejete() {
        String token = jwt.sign(Map.of("sub", 1L, "typ", "access"), -10);

        assertThatThrownBy(() -> jwt.verify(token))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token expire");
    }

    @Test
    void expirationInvalide_rejetee() {
        String token = jwt.sign(Map.of("sub", 1L, "exp", 0), 300);
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(JwtException.class)
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
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":1,\"exp\":9999999999,\"iss\":\"finrisk360\"}".getBytes());

        assertThatThrownBy(() -> jwt.verify(header + "." + payload + "."))
                .isInstanceOf(JwtException.class)
                .hasMessage("Algorithme non permis");
    }

    @Test
    void emetteurInconnu_rejete() {
        String token = autreEmetteur.sign(Map.of("sub", 1L, "typ", "access"), 300);

        assertThatThrownBy(() -> jwt.verify(token))
                .isInstanceOf(JwtException.class)
                .hasMessage("Emetteur inconnu");
    }

    @Test
    void tokenMalforme_rejete() {
        assertThatThrownBy(() -> jwt.verify("un-seul-segment"))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token malforme");
        assertThatThrownBy(() -> jwt.verify(null))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.verify("a.!.c"))
                .isInstanceOf(JwtException.class);
    }
}
