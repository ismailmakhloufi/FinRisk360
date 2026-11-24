package tn.finrisk.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.domain.UserRole;
import tn.finrisk.authservice.repository.AppUserRepository;
import tn.finrisk.authservice.security.JwtException;
import tn.finrisk.authservice.web.dto.LoginRequest;
import tn.finrisk.authservice.web.dto.RefreshRequest;
import tn.finrisk.authservice.web.dto.TokenResponse;
import tn.finrisk.authservice.web.error.AuthenticationFailedException;
import tn.finrisk.authservice.web.error.ForbiddenException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private AuditService auditService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, tokenService, auditService);
    }

    private AppUser user(boolean actif) {
        AppUser u = new AppUser();
        u.setUserId(7L);
        u.setUsername("fatou");
        u.setPasswordHash("$2a$fakehash");
        u.setRole(UserRole.ANALYST);
        u.setActive(actif);
        return u;
    }

    private void paireAttendue(AppUser u) {
        when(tokenService.issuePair(u)).thenReturn(
                new TokenService.TokenPair("access-xyz", "refresh-xyz", 900));
    }

    // ------------------------------------------------------------- LOGIN

    @Test
    void login_reussi_renvoieLesDeuxTokensEtTraceLeSucces() {
        AppUser u = user(true);
        when(userRepository.findByUsername("fatou")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("motdepasse", "$2a$fakehash")).thenReturn(true);
        paireAttendue(u);

        TokenResponse response = authService.login(new LoginRequest("fatou", "motdepasse"), "1.2.3.4");

        assertThat(response.accessToken()).isEqualTo("access-xyz");
        assertThat(response.refreshToken()).isEqualTo("refresh-xyz");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.user().username()).isEqualTo("fatou");
        assertThat(response.user().role()).isEqualTo(UserRole.ANALYST);
        verify(auditService).record(eq(7L), eq("LOGIN_SUCCESS"), eq("USER"), eq(7L),
                isNull(), eq("role=ANALYST"), eq("1.2.3.4"));
    }

    @Test
    void login_usernameInconnu_401EtTraceSansAuteur() {
        when(userRepository.findByUsername("fantome")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("fantome", "x"), null))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Identifiants invalides");

        verify(auditService).recordRefus(isNull(), eq("LOGIN_FAILED"), eq("USER"), eq(0L),
                isNull(), anyString(), isNull());
        verify(tokenService, never()).issuePair(any());
    }

    @Test
    void login_mauvaisMotDePasse_401EtTrace() {
        AppUser u = user(true);
        when(userRepository.findByUsername("fatou")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("faux", "$2a$fakehash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("fatou", "faux"), null))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Identifiants invalides");

        verify(auditService).recordRefus(eq(7L), eq("LOGIN_FAILED"), eq("USER"), eq(7L),
                isNull(), eq("Mot de passe incorrect"), isNull());
    }

    @Test
    void login_compteDesactive_403Pas401() {
        AppUser u = user(false);
        when(userRepository.findByUsername("fatou")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("motdepasse", "$2a$fakehash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("fatou", "motdepasse"), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Compte desactive");

        verify(auditService).recordRefus(eq(7L), eq("LOGIN_FAILED"), eq("USER"), eq(7L),
                eq("active=true"), eq("Compte desactive"), isNull());
        verify(tokenService, never()).issuePair(any());
    }

    // ------------------------------------------------------------ REFRESH

    @Test
    void refresh_renvoieUneNouvellePaire() {
        AppUser u = user(true);
        when(tokenService.verifyRefresh("r-token")).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode()
                        .put("sub", 7L).put("rol", "ANALYST"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));
        paireAttendue(u);

        TokenResponse response = authService.refresh(new RefreshRequest("r-token"), null);

        assertThat(response.accessToken()).isEqualTo("access-xyz");
        assertThat(response.user().id()).isEqualTo(7L);
    }

    @Test
    void refresh_compteDesactive_403() {
        AppUser u = user(false);
        when(tokenService.verifyRefresh("r-token")).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode()
                        .put("sub", 7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("r-token"), null))
                .isInstanceOf(ForbiddenException.class);
        verify(tokenService, never()).issuePair(any());
    }

    @Test
    void refresh_tokenInvalide_401() {
        when(tokenService.verifyRefresh("pires-token"))
                .thenThrow(new JwtException("Token expire"));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("pires-token"), null))
                .isInstanceOf(JwtException.class);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------------------- ME

    @Test
    void me_sansHeader_401() {
        assertThatThrownBy(() -> authService.me(null))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("Token manquant");
    }

    @Test
    void me_renvoieLeProfil() {
        AppUser u = user(true);
        when(tokenService.verifyAccess("a-token")).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode()
                        .put("sub", 7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));

        assertThat(authService.me("Bearer a-token").username()).isEqualTo("fatou");
        assertThat(authService.me("Bearer a-token").active()).isTrue();
    }

    @Test
    void me_compteDesactive_403() {
        AppUser u = user(false);
        when(tokenService.verifyAccess("a-token")).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode()
                        .put("sub", 7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> authService.me("Bearer a-token"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void me_utilisateurSupprime_401() {
        when(tokenService.verifyAccess("a-token")).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode()
                        .put("sub", 999L));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.me("Bearer a-token"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Token invalide");
    }
}
