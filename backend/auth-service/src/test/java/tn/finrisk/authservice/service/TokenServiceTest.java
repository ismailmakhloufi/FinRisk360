package tn.finrisk.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.domain.UserRole;
import tn.finrisk.authservice.security.JwtException;
import tn.finrisk.authservice.security.JwtSupport;

class TokenServiceTest {

    private final JwtSupport jwt =
            new JwtSupport("test-secret-hmac-sha256-minimum-32-chars-2026", "finrisk360");
    private final TokenService service = new TokenService(jwt, 15, 7);

    private AppUser user(UserRole role) {
        AppUser u = new AppUser();
        u.setUserId(42L);
        u.setUsername("cheikh");
        u.setRole(role);
        return u;
    }

    @Test
    void issuePair_porteLesClaimsDuContrat() {
        TokenService.TokenPair pair = service.issuePair(user(UserRole.MANAGER));

        JsonNode access = jwt.verify(pair.accessToken());
        assertThat(access.path("sub").asLong()).isEqualTo(42L);
        assertThat(access.path("usr").asText()).isEqualTo("cheikh");
        assertThat(access.path("rol").asText()).isEqualTo("MANAGER");
        assertThat(access.path("typ").asText()).isEqualTo("access");
        assertThat(pair.expiresIn()).isEqualTo(15 * 60);

        JsonNode refresh = jwt.verify(pair.refreshToken());
        assertThat(refresh.path("typ").asText()).isEqualTo("refresh");
        // le refresh dure 7 jours, l'access 15 min : exp du refresh > exp de l'access
        assertThat(refresh.path("exp").asLong())
                .isGreaterThan(access.path("exp").asLong());
    }

    @Test
    void verifyAccess_rejetteUnRefreshJoueEnAccess() {
        String refresh = jwt.sign(Map.of("sub", 1L, "typ", "refresh"), 600);

        assertThatThrownBy(() -> service.verifyAccess(refresh))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("access");
    }

    @Test
    void verifyRefresh_rejetteUnAccessJoueEnRefresh() {
        String access = jwt.sign(Map.of("sub", 1L, "typ", "access"), 600);

        assertThatThrownBy(() -> service.verifyRefresh(access))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("refresh");
    }

    @Test
    void accessExpire_rejete() {
        // signe avec une duree de vie NEGATIVE : exp deja passe au moment du verify
        String vieux = jwt.sign(Map.of("sub", 1L, "typ", "access"), -5);

        assertThatThrownBy(() -> service.verifyAccess(vieux))
                .isInstanceOf(JwtException.class)
                .hasMessage("Token expire");
    }
}
