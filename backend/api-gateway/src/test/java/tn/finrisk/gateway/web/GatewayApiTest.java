package tn.finrisk.gateway.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.gateway.security.JwtSupport;

/**
 * LE jalon M5 : 401 sans token, 403 role insuffisant — verifiees contre un
 * vrai sous-serveur HTTP (JDK com.sun.net.httpserver, zero dependance) qui
 * joue le backend cible et inspecte ce que le proxy lui envoie.
 *
 * Override des UPSTREAMS (cles nommees) via @DynamicPropertySource :
 *   customer/transaction/ai/auth -> ce stub : passthrough
 *   alert-case                   -> :9      : test 503 (cible arretee)
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
class GatewayApiTest {

    private static HttpServer stub;
    private static volatile String lastMethod;
    private static volatile String lastPath;
    private static volatile String lastQuery;
    private static volatile String lastUserId;
    private static volatile String lastUserRole;
    private static volatile String lastForwardedFor;
    private static volatile String lastBody;
    private static volatile int appels;

    /**
     * Override des UPSTREAMS (cles scalaires, pas d'index de liste) :
     *   customer   -> ce stub    : tests de passthrough
     *   alert-case -> localhost:9: test 503 (audit = cible arretee)
     *   auth       -> ce stub    : test de route publique (login)
     */
    @DynamicPropertySource
    static void cibles(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();
            lastUserId = exchange.getRequestHeaders().getFirst("X-User-Id");
            lastUserRole = exchange.getRequestHeaders().getFirst("X-User-Role");
            lastForwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            appels++;
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();

        String base = "http://localhost:" + stub.getAddress().getPort();
        registry.add("gateway.upstreams.customer", () -> base);
        registry.add("gateway.upstreams.transaction", () -> base);
        registry.add("gateway.upstreams.alert-case", () -> "http://localhost:9");
        registry.add("gateway.upstreams.ai", () -> base);
        registry.add("gateway.upstreams.auth", () -> base);
    }

    @AfterAll
    static void arreterStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtSupport jwt;

    /** Meme contrat de claims que le auth-service l'emettrait. */
    private String token(long sub, String role, long ttlSecondes) {
        return jwt.sign(Map.of("sub", sub, "usr", "u" + sub, "rol", role,
                "typ", "access"), ttlSecondes);
    }

    private String bearer(long sub, String role) {
        return "Bearer " + token(sub, role, 300);
    }

    // ------------------------------------------------------------- 401

    @Test
    void simulatorRoleIsLimitedToBankOperations() throws Exception {
        String authorization = bearer(7, "BANK_SIMULATOR");
        mvc.perform(get("/api/customers/1").header("Authorization", authorization))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rules").header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users").header("Authorization", authorization))
                .andExpect(status().isForbidden());
    }

    @Test
    void sansToken_401EtAucunContactAvecLeBackend() throws Exception {
        int avant = appels;

        mvc.perform(get("/api/customers/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value(
                        "Token manquant (header Authorization : Bearer <token>)"))
                .andExpect(jsonPath("$.path").value("/api/customers/1"));

        org.assertj.core.api.Assertions.assertThat(appels).isEqualTo(avant);
    }

    @Test
    void tokenSansPrefixeBearer_401() throws Exception {
        mvc.perform(get("/api/customers/1").header("Authorization", token(1, "ADMIN", 300)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("Format de token invalide (attendu : Bearer <token>)"));
    }

    @Test
    void tokenForge_401() throws Exception {
        String volle = token(1, "ADMIN", 300);
        String forge = volle.substring(0, volle.length() - 4) + "zzzz";

        mvc.perform(get("/api/customers/1").header("Authorization", "Bearer " + forge))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenExpire_401() throws Exception {
        mvc.perform(get("/api/customers/1")
                        .header("Authorization", "Bearer " + token(1, "ADMIN", -10)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token expire"));
    }

    @Test
    void tokenDejaDelaServeur_401MemeMessage() throws Exception {
        mvc.perform(get("/api/customers/1").header("Authorization", "Bearer n importe quoi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token malforme"));
    }

    // ------------------------------------------------------------- 403

    @Test
    void auditAnalyst_403RoleInsuffisant() throws Exception {
        mvc.perform(get("/api/audit/page").header("Authorization", bearer(2, "ANALYST")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message")
                        .value("Rôle insuffisant : MANAGER ou ADMIN requis (votre rôle : ANALYST)"));
    }

    @Test
    void ecritureRegles_analyst_403() throws Exception {
        // la matrice dit : POST /api/rules -> ["ADMIN"] uniquement
        mvc.perform(post("/api/rules").header("Authorization", bearer(2, "ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Rôle insuffisant : ADMIN requis (votre rôle : ANALYST)"));
    }

    @Test
    void ecritureCustomers_analyst_403MaisManagers_200() throws Exception {
        mvc.perform(post("/api/customers").header("Authorization", bearer(2, "ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/customers").header("Authorization", bearer(3, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nom\":\"Diallo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        org.assertj.core.api.Assertions.assertThat(lastMethod).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(lastBody).isEqualTo("{\"nom\":\"Diallo\"}");
    }

    // ---------------------------------------------------- PASSTHROUGH

    @Test
    void lectureValide_proxyeAvecIdentiteDeConfiance() throws Exception {
        mvc.perform(get("/api/customers/1").header("Authorization", bearer(7, "ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        org.assertj.core.api.Assertions.assertThat(lastMethod).isEqualTo("GET");
        org.assertj.core.api.Assertions.assertThat(lastPath).isEqualTo("/api/customers/1");
        org.assertj.core.api.Assertions.assertThat(lastUserId).isEqualTo("7");
        org.assertj.core.api.Assertions.assertThat(lastUserRole).isEqualTo("ANALYST");
        org.assertj.core.api.Assertions.assertThat(lastForwardedFor).isEqualTo("127.0.0.1");
    }

    @Test
    void antispoofing_lesHeadersClientSontEcrases() throws Exception {
        mvc.perform(get("/api/customers/1")
                        .header("Authorization", bearer(5, "ANALYST"))
                        .header("X-User-Id", "999")
                        .header("X-User-Role", "ADMIN")
                        .header("X-Forwarded-For", "6.6.6.6"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(lastUserId).isEqualTo("5");
        org.assertj.core.api.Assertions.assertThat(lastUserRole).isEqualTo("ANALYST");
        org.assertj.core.api.Assertions.assertThat(lastForwardedFor).isEqualTo("127.0.0.1");
    }

    @Test
    void methodeEtQueryPreservees() throws Exception {
        // la query dans l'URL (comme un navigateur) : getQueryString() doit
        // ressortir telle quelle cote cible
        mvc.perform(get("/api/customers?page=0&size=5")
                        .header("Authorization", bearer(1, "MANAGER")))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(lastMethod).isEqualTo("GET");
        org.assertj.core.api.Assertions.assertThat(lastQuery).isEqualTo("page=0&size=5");

        mvc.perform(delete("/api/customers/9").header("Authorization", bearer(1, "MANAGER")))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(lastMethod).isEqualTo("DELETE");
        org.assertj.core.api.Assertions.assertThat(lastPath).isEqualTo("/api/customers/9");
    }

    @Test
    void lectureAvecToutRoleAutorise_200() throws Exception {
        mvc.perform(get("/api/transactions/1").header("Authorization", bearer(2, "ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------ ROUTES SPECIALES

    @Test
    void analyseAi_analyst_200Proxyee() throws Exception {
        // la matrice M6 dit : POST /api/ai/** -> ["ANALYST", "ADMIN"]
        mvc.perform(post("/api/ai/alerts/42/analyze")
                        .header("Authorization", bearer(2, "ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        org.assertj.core.api.Assertions.assertThat(lastPath)
                .isEqualTo("/api/ai/alerts/42/analyze");
        org.assertj.core.api.Assertions.assertThat(lastUserRole).isEqualTo("ANALYST");
        org.assertj.core.api.Assertions.assertThat(lastUserId).isEqualTo("2");
    }

    @Test
    void chatAi_manager_403RoleInsuffisant() throws Exception {
        mvc.perform(post("/api/ai/chat")
                        .header("Authorization", bearer(3, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertId\":42,\"message\":\"résumé\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Rôle insuffisant : ANALYST ou ADMIN requis (votre rôle : MANAGER)"));
    }

    @Test
    void analyseAi_sansToken_401() throws Exception {
        mvc.perform(post("/api/ai/alerts/42/analyze"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void routePubliqueLogin_passeMemeSansToken() throws Exception {
        int avant = appels;

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        org.assertj.core.api.Assertions.assertThat(appels).isEqualTo(avant + 1);
        org.assertj.core.api.Assertions.assertThat(lastPath).isEqualTo("/api/auth/login");
        // la route publique n'a PAS d'identite a revendiquer
        org.assertj.core.api.Assertions.assertThat(lastUserId).isNull();
    }

    @Test
    void routeInconnue_404EmisParLeGateway() throws Exception {
        mvc.perform(get("/api/zzz/inconnu").header("Authorization", bearer(1, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Route inconnue : /api/zzz/inconnu"));
    }

    @Test
    void cibleArretee_503_apresLecontroleDuToken() throws Exception {
        // 1) sans token -> c'est le 401 qui gagne (le garde passe avant le proxy)
        mvc.perform(get("/api/audit/page"))
                .andExpect(status().isUnauthorized());

        // 2) token valide -> le proxy essaie la cible morte -> 503
        mvc.perform(get("/api/audit/page").header("Authorization", bearer(3, "MANAGER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(
                        "Service cible indisponible : http://localhost:9 (arrete ou en erreur)"));
    }

    @Test
    void actuatorEstLocal_nonProxye() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
