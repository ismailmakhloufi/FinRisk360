package tn.finrisk.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.domain.UserRole;
import tn.finrisk.authservice.repository.AppUserRepository;
import tn.finrisk.authservice.repository.AuditLogRepository;
import tn.finrisk.authservice.security.JwtSupport;

/**
 * Boucle complete sur finrisk_test (comme le reste du projet : meme base
 * que la demo). Comptes crees ICI avec hash BCrypt reel + suffixe unique
 * par run : aucune dependance au seed, aucun nettoyage agressif (les
 * utilisateurs crees par ce test sont supprimes en fin de methode ; la FK
 * audit_logs ON DELETE SET NULL rend cela sans risque).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private BCryptPasswordEncoder encoder;
    @Autowired
    private JwtSupport jwt;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private String adminName;
    private String adminPass = "admin-it-12345";

    @BeforeEach
    void creerComptes() {
        adminName = "it-admin-" + suffix;
        creer(adminName, adminPass, UserRole.ADMIN);
        creer("it-ana-" + suffix, "analyst-it-12345", UserRole.ANALYST);
    }

    @AfterEach
    void nettoyer() {
        userRepository.findAll().stream()
                .filter(u -> u.getUsername().startsWith("it-"))
                .toList()
                .forEach(userRepository::delete);
    }

    private AppUser creer(String username, String password, UserRole role) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setActive(true);
        return userRepository.save(u);
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String token(JsonNode login) {
        return "Bearer " + login.path("accessToken").asText();
    }

    // ----------------------------------------------------------- LOGIN/ME

    @Test
    void loginEtMe_boucleComplete() throws Exception {
        JsonNode body = login(adminName, adminPass);

        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("user").path("role").asText()).isEqualTo("ADMIN");
        assertThat(body.path("refreshToken").asText()).isNotBlank();

        mvc.perform(get("/api/auth/me").header("Authorization", token(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(adminName))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void login_mauvaisMotDePasse_401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + adminName + "\",\"password\":\"non\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Identifiants invalides"));
    }

    @Test
    void login_usernameInconnu_401MemeMessage() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"personne-" + suffix + "\",\"password\":\"non\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Identifiants invalides"));
    }

    @Test
    void tokenForge_401SurMe() throws Exception {
        String volle = login(adminName, adminPass).path("accessToken").asText();
        String forge = volle.substring(0, volle.length() - 3) + "abc";

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forge))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_renouvelleLAccess() throws Exception {
        JsonNode body = login(adminName, adminPass);

        MvcResult result = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + body.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode neuf = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(neuf.path("accessToken").asText())
                .isNotEqualTo(body.path("accessToken").asText());
        // le nouveau access passe la verification interne du service
        mvc.perform(get("/api/auth/me").header("Authorization", token(neuf)))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_avecUnAccess_401() throws Exception {
        JsonNode body = login(adminName, adminPass);

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + body.path("accessToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------- ADMIN CRUD

    @Test
    void adminCreeUnCompte_puisLeNouvelUtilisateurSeConnecte() throws Exception {
        String adm = token(login(adminName, adminPass));
        String nouveau = "it-cree-" + suffix;

        MvcResult result = mvc.perform(post("/api/users").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + nouveau + "\",\"email\":\"" + nouveau
                                + "@x.io\",\"password\":\"motdepasse1\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Location"))
                .startsWith("/api/users/");

        login(nouveau, "motdepasse1");
    }

    @Test
    void nonAdminNeVoitPasLesUtilisateurs_403() throws Exception {
        String ana = token(login("it-ana-" + suffix, "analyst-it-12345"));

        mvc.perform(get("/api/users").header("Authorization", ana))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void users_sansToken_401() throws Exception {
        mvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doubleGarde_directSur8087_sansGateway_403QuandMeme() throws Exception {
        // appel DIRECT au service (bypass potentiel du gateway) : le controle
        // interne doit tenir tout seul
        String ana = token(login("it-ana-" + suffix, "analyst-it-12345"));

        mvc.perform(post("/api/users").header("Authorization", ana)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it-bis-" + suffix
                                + "\",\"password\":\"motdepasse1\",\"role\":\"ANALYST\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDesactiveUnCompte_puisLogin_403() throws Exception {
        String adm = token(login(adminName, adminPass));
        String cible = "it-ana-" + suffix;
        long id = userRepository.findByUsername(cible).orElseThrow().getUserId();

        mvc.perform(patch("/api/users/" + id).header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + cible + "\",\"password\":\"analyst-it-12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Compte desactive"));
    }

    @Test
    void usernameDejaPris_409() throws Exception {
        String adm = token(login(adminName, adminPass));

        mvc.perform(post("/api/users").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + adminName
                                + "\",\"password\":\"motdepasse1\",\"role\":\"ANALYST\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchSansRien_400() throws Exception {
        String adm = token(login(adminName, adminPass));
        long id = userRepository.findByUsername("it-ana-" + suffix).orElseThrow().getUserId();

        mvc.perform(patch("/api/users/" + id).header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------- AUDIT

    @Test
    void chaqueLoginReussiLaisseUneTraceAUTH() throws Exception {
        long id = userRepository.findByUsername(adminName).orElseThrow().getUserId();

        login(adminName, adminPass);

        assertThat(auditLogRepository.findByActionOrderByCreatedAtDesc("LOGIN_SUCCESS"))
                .filteredOn(a -> a.getUserId() != null && a.getUserId() == id)
                .isNotEmpty()
                .allSatisfy(a -> {
                    assertThat(a.getEntityType()).isEqualTo("USER");
                    assertThat(a.getNewValue()).contains("role=ADMIN");
                });
    }

    @Test
    void loginEchoueLaisseAussiUneTrace() throws Exception {
        long id = userRepository.findByUsername(adminName).orElseThrow().getUserId();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + adminName + "\",\"password\":\"non\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(auditLogRepository.findByActionOrderByCreatedAtDesc("LOGIN_FAILED"))
                .filteredOn(a -> a.getUserId() != null && a.getUserId() == id)
                .isNotEmpty();
    }

    @Test
    void leHashDuMotDePasseNestJamaisUnClairNiUnDoublon() throws Exception {
        String autre = "it-hash-" + suffix;
        creer(autre, adminPass, UserRole.ANALYST);

        String hashAdmin = userRepository.findByUsername(adminName).orElseThrow().getPasswordHash();
        String hashAutre = userRepository.findByUsername(autre).orElseThrow().getPasswordHash();

        assertThat(hashAdmin).startsWith("$2").isNotEqualTo(hashAutre);
        assertThat(hashAdmin).isNotEqualTo(adminPass);
        // meme mot de passe, deux salts -> deux hashes distincts (BCrypt)
        assertThat(encoder.matches(adminPass, hashAdmin)).isTrue();
        assertThat(encoder.matches(adminPass, hashAutre)).isTrue();
    }
}
