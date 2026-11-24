package tn.finrisk.authservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.authservice.domain.UserRole;
import tn.finrisk.authservice.security.JwtException;
import tn.finrisk.authservice.service.AuthService;
import tn.finrisk.authservice.service.UserAdminService;
import tn.finrisk.authservice.web.dto.PasswordResetResponse;
import tn.finrisk.authservice.web.dto.TokenResponse;
import tn.finrisk.authservice.web.dto.UserProfile;
import tn.finrisk.authservice.web.dto.UserResponse;
import tn.finrisk.authservice.web.error.AuthenticationFailedException;
import tn.finrisk.authservice.web.error.BadRequestException;
import tn.finrisk.authservice.web.error.ConflictException;
import tn.finrisk.authservice.web.error.ForbiddenException;
import tn.finrisk.authservice.web.error.UserNotFoundException;

/**
 * La couche web SEULE (services faux) : forme des reponses, codes 401/403
 * exigees par le jalon M5, Location sur POST, corps d'erreur standard.
 */
@WebMvcTest({AuthController.class, UserController.class})
class AuthApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserAdminService userAdminService;

    // -------------------------------------------------------------- LOGIN

    @Test
    void login_200_renvoieTokensEtProfil() throws Exception {
        when(authService.login(any(), any())).thenReturn(new TokenResponse(
                "acc-1", "ref-1", "Bearer", 900,
                new UserProfile(1L, "admin", null, UserRole.ADMIN, true)));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc-1"))
                .andExpect(jsonPath("$.refreshToken").value("ref-1"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void login_corpsVide_400AvecErreursDeChamp() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                // l'ordre des violations de validation n'est pas garanti par
                // Bean Validation : on compare en tant que SET de champs
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("username", "password")));
    }

    @Test
    void login_identifiantsFautifs_401() throws Exception {
        when(authService.login(any(), any()))
                .thenThrow(new AuthenticationFailedException("Identifiants invalides"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Identifiants invalides"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    @Test
    void login_compteDesactive_403() throws Exception {
        when(authService.login(any(), any()))
                .thenThrow(new ForbiddenException("Compte desactive"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void refresh_401SiTokenInvalide() throws Exception {
        when(authService.refresh(any(), any())).thenThrow(new JwtException("Token expire"));

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"pff\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token expire"));
    }

    // ---------------------------------------------------------------- ME

    @Test
    void me_200() throws Exception {
        when(authService.me("Bearer acc"))
                .thenReturn(new UserProfile(2L, "fatou", "f@x.io", UserRole.ANALYST, true));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer acc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.role").value("ANALYST"));
    }

    @Test
    void me_sansToken_401() throws Exception {
        when(authService.me(null)).thenThrow(new JwtException("Token manquant"));

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // -------------------------------------------------------------- USERS

    @Test
    void users_liste_200() throws Exception {
        when(userAdminService.list("Bearer adm")).thenReturn(List.of(
                new UserResponse(1L, "admin", null, UserRole.ADMIN, true, null),
                new UserResponse(2L, "fatou", "f@x.io", UserRole.ANALYST, true, null)));

        mvc.perform(get("/api/users").header("Authorization", "Bearer adm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("ADMIN"));
    }

    @Test
    void users_nonAdmin_403() throws Exception {
        when(userAdminService.list("Bearer ana"))
                .thenThrow(new ForbiddenException("Acces reserve aux administrateurs (role ADMIN requis)"));

        mvc.perform(get("/api/users").header("Authorization", "Bearer ana"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(
                        "Acces reserve aux administrateurs (role ADMIN requis)"));
    }

    @Test
    void users_sansToken_401() throws Exception {
        when(userAdminService.list(null)).thenThrow(new JwtException("Token manquant"));

        mvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void users_post_201AvecLocation() throws Exception {
        when(userAdminService.create(eq("Bearer adm"), any())).thenReturn(
                new UserResponse(9L, "moussa", null, UserRole.MANAGER, true, null));

        mvc.perform(post("/api/users").header("Authorization", "Bearer adm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"moussa\",\"password\":\"secret123\","
                                + "\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/users/9"))
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.username").value("moussa"));
    }

    @Test
    void users_post_usernamePris_409() throws Exception {
        when(userAdminService.create(eq("Bearer adm"), any()))
                .thenThrow(new ConflictException("Nom d'utilisateur deja pris : admin"));

        mvc.perform(post("/api/users").header("Authorization", "Bearer adm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret123\","
                                + "\"role\":\"ANALYST\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void users_getId_inconnu_404() throws Exception {
        when(userAdminService.get("Bearer adm", 404L))
                .thenThrow(new UserNotFoundException(404L));

        mvc.perform(get("/api/users/404").header("Authorization", "Bearer adm"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Utilisateur 404 introuvable"));
    }

    @Test
    void users_patch_patchVide_400() throws Exception {
        when(userAdminService.update(eq("Bearer adm"), eq(3L), any()))
                .thenThrow(new BadRequestException("Aucune modification fournie (role, active ou password)"));

        mvc.perform(patch("/api/users/3").header("Authorization", "Bearer adm")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void users_patch_ok_200() throws Exception {
        when(userAdminService.update(eq("Bearer adm"), eq(3L), any())).thenReturn(
                new UserResponse(3L, "fatou", null, UserRole.MANAGER, true, null));

        mvc.perform(patch("/api/users/3").header("Authorization", "Bearer adm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    // --------------------------------------------------- REINIT MOT DE PASSE

    @Test
    void users_postPassword_ok_200_renvoieLeClairUneFois() throws Exception {
        when(userAdminService.resetPassword("Bearer adm", 3L))
                .thenReturn(new PasswordResetResponse(3L, "fatou", "aB3-kmQ7xWz2"));

        mvc.perform(post("/api/users/3/password").header("Authorization", "Bearer adm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.username").value("fatou"))
                .andExpect(jsonPath("$.password").value("aB3-kmQ7xWz2"));
    }

    @Test
    void users_postPassword_analyst_403() throws Exception {
        when(userAdminService.resetPassword("Bearer ana", 3L))
                .thenThrow(new ForbiddenException("Acces reserve aux administrateurs (role ADMIN requis)"));

        mvc.perform(post("/api/users/3/password").header("Authorization", "Bearer ana"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void users_postPassword_inconnu_404() throws Exception {
        when(userAdminService.resetPassword("Bearer adm", 404L))
                .thenThrow(new UserNotFoundException(404L));

        mvc.perform(post("/api/users/404/password").header("Authorization", "Bearer adm"))
                .andExpect(status().isNotFound());
    }
}
