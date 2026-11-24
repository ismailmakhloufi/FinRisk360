package tn.finrisk.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Le gateway n'a ni base ni JPA : le contexte monte sur la seule lecture
 * du yml (routes, secret partage).
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class GatewayApplicationTests {

    @Autowired
    private tn.finrisk.gateway.config.GatewayProperties props;

    @Test
    void contexteMonte_etLesRoutesSontChargees() {
        assertThat(props.routes()).isNotEmpty();
        assertThat(props.match("/api/customers/1")).isNotNull();
        assertThat(props.match("/api/zzz/inconnu")).isNull();
        assertThat(props.isPublic("/api/auth/login")).isTrue();
        assertThat(props.isPublic("/api/customers")).isFalse();
    }
}
