package tn.finrisk.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.domain.UserRole;
import tn.finrisk.authservice.repository.AppUserRepository;
import tn.finrisk.authservice.security.JwtException;
import tn.finrisk.authservice.web.dto.UserCreateRequest;
import tn.finrisk.authservice.web.dto.UserUpdateRequest;
import tn.finrisk.authservice.web.error.BadRequestException;
import tn.finrisk.authservice.web.error.ConflictException;
import tn.finrisk.authservice.web.error.ForbiddenException;
import tn.finrisk.authservice.web.error.UserNotFoundException;

/**
 * Le coeur du jalon M5 : la gachette ADMIN est tiree DEUX fois — le
 * gateway (non teste ici) et ce service, qui rejette meme un appel direct
 * sur :8087 avec un access-token ANALYST pourtant parfaitement valide.
 */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private AuditService auditService;

    private UserAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userRepository, passwordEncoder, tokenService, auditService);
    }

    // --------------------------------------------------------------- AIDES

    private JsonNode claims(long sub, String rol) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("sub", sub);
        node.put("rol", rol);
        return node;
    }

    private AppUser appelant() {
        AppUser admin = new AppUser();
        admin.setUserId(1L);
        admin.setUsername("admin");
        admin.setRole(UserRole.ADMIN);
        return admin;
    }

    private AppUser cible(long id, UserRole role, boolean actif) {
        AppUser u = new AppUser();
        u.setUserId(id);
        u.setUsername("cible-" + id);
        u.setRole(role);
        u.setActive(actif);
        return u;
    }

    // ------------------------------------------------------------ GARDIEN

    @Test
    void sansToken_401() {
        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenAnalyst_403MemePourLaLecture() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(2L, "ANALYST"));

        assertThatThrownBy(() -> service.list("Bearer t"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void get_avecTokenAnalyst_403Egalement() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(2L, "ANALYST"));

        assertThatThrownBy(() -> service.get("Bearer t", 5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_admin_renvoieLaListe() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findAllByOrderByUserIdAsc())
                .thenReturn(List.of(cible(2L, UserRole.ANALYST, true)));

        var liste = service.list("Bearer t");

        assertThat(liste).hasSize(1);
        assertThat(liste.get(0).id()).isEqualTo(2L);
        assertThat(liste.get(0).role()).isEqualTo(UserRole.ANALYST);
        // UserResponse n'a AUCUN champ passwordHash : le hash ne quitte pas le service
        assertThat(liste.get(0)).isNotNull();
    }

    // -------------------------------------------------------------- CREATE

    @Test
    void create_hashLeMotDePasseEtTrace() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.existsByUsername("moussa")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hash");
        AppUser sauve = cible(9L, UserRole.MANAGER, true);
        sauve.setUsername("moussa");
        when(userRepository.save(any(AppUser.class))).thenReturn(sauve);

        var reponse = service.create("Bearer t",
                new UserCreateRequest("moussa", "m@x.io", "secret123", UserRole.MANAGER));

        assertThat(reponse.id()).isEqualTo(9L);
        assertThat(reponse.role()).isEqualTo(UserRole.MANAGER);
        verify(userRepository).save(
                org.mockito.ArgumentMatchers.argThat(u -> "$2a$hash".equals(u.getPasswordHash())));
        verify(auditService).record(eq(1L), eq("USER_CREATED"), eq("USER"), eq(9L),
                isNull(), eq("username=moussa, role=MANAGER"), isNull());
    }

    @Test
    void create_usernamePris_409() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.existsByUsername("moussa")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Bearer t",
                new UserCreateRequest("moussa", null, "secret123", UserRole.ANALYST)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("deja pris");
    }

    @Test
    void create_emailPris_409() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.existsByUsername("libre")).thenReturn(false);
        when(userRepository.existsByEmail("pris@x.io")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Bearer t",
                new UserCreateRequest("libre", "pris@x.io", "secret123", UserRole.ANALYST)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("e-mail");
    }

    // -------------------------------------------------------------- UPDATE

    @Test
    void update_patchVide_400() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));

        assertThatThrownBy(() -> service.update("Bearer t", 3L,
                new UserUpdateRequest(null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Aucune modification");
    }

    @Test
    void update_inconnu_404() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("Bearer t", 404L,
                new UserUpdateRequest(UserRole.ANALYST, null, null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void update_changeRoleEtTraceAvantApres() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        AppUser cible = cible(3L, UserRole.ANALYST, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(cible));
        when(userRepository.save(cible)).thenReturn(cible);

        service.update("Bearer t", 3L, new UserUpdateRequest(UserRole.MANAGER, null, null));

        assertThat(cible.getRole()).isEqualTo(UserRole.MANAGER);
        verify(auditService).record(eq(1L), eq("USER_UPDATED"), eq("USER"), eq(3L),
                eq("role=ANALYST, active=true"), eq("role=MANAGER, active=true"), isNull());
    }

    @Test
    void update_desactiveUnAutre_estTrace() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        AppUser cible = cible(3L, UserRole.ANALYST, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(cible));
        when(userRepository.save(cible)).thenReturn(cible);

        service.update("Bearer t", 3L, new UserUpdateRequest(null, false, null));

        assertThat(cible.isActive()).isFalse();
        verify(auditService).record(eq(1L), eq("USER_UPDATED"), eq("USER"), eq(3L),
                eq("role=ANALYST, active=true"), eq("role=ANALYST, active=false"), isNull());
    }

    @Test
    void update_reinitMotDePasse_neLaisseJamaisLaTraceDuMotDePasse() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        AppUser cible = cible(3L, UserRole.ANALYST, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(cible));
        when(userRepository.save(cible)).thenReturn(cible);
        when(passwordEncoder.encode("nouveau123")).thenReturn("$2a$nouveau");

        service.update("Bearer t", 3L, new UserUpdateRequest(null, null, "nouveau123"));

        assertThat(cible.getPasswordHash()).isEqualTo("$2a$nouveau");
        verify(auditService).record(eq(1L), eq("USER_UPDATED"), eq("USER"), eq(3L),
                eq("role=ANALYST, active=true"), eq("role=ANALYST, active=true, mot de passe reinitialise"), isNull());
    }

    @Test
    void update_adminSeDesactive_409AntiLockout() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(appelant()));

        assertThatThrownBy(() -> service.update("Bearer t", 1L,
                new UserUpdateRequest(null, false, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("propre compte");
    }

    @Test
    void update_adminChangeSonRole_409AntiLockout() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(appelant()));

        assertThatThrownBy(() -> service.update("Bearer t", 1L,
                new UserUpdateRequest(UserRole.ANALYST, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("propre role");
    }

    // ---------------------------------------------------------------- GET

    @Test
    void get_admin_404SiInconnu() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("Bearer t", 77L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("77");
    }

    @Test
    void get_admin_200Sinon() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findById(3L)).thenReturn(Optional.of(cible(3L, UserRole.MANAGER, true)));

        assertThat(service.get("Bearer t", 3L).username()).isEqualTo("cible-3");
        assertThat(service.get("Bearer t", 3L).role()).isEqualTo(UserRole.MANAGER);
    }

    // -------------------------------------------------- RESET SUR GENERATION

    @Test
    void reset_avecTokenAnalyst_403() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(2L, "ANALYST"));

        assertThatThrownBy(() -> service.resetPassword("Bearer t", 3L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void reset_inconnu_404() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("Bearer t", 404L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void reset_renvoieLeClairUneFois_hashEtTraceSansLeMotDePasse() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        AppUser cible = cible(3L, UserRole.ANALYST, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(cible));
        when(userRepository.save(cible)).thenReturn(cible);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$genere");

        var reponse = service.resetPassword("Bearer t", 3L);

        // Le clair est rendu a l'appelant...
        assertThat(reponse.id()).isEqualTo(3L);
        assertThat(reponse.username()).isEqualTo("cible-3");
        assertThat(reponse.password()).isNotBlank().hasSizeGreaterThanOrEqualTo(8);
        // ...et la base ne garde QUE le hash.
        assertThat(cible.getPasswordHash()).isEqualTo("$2a$genere");

        ArgumentCaptor<String> clair = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(clair.capture());
        assertThat(clair.getValue()).isEqualTo(reponse.password());

        // La trace dit QUELLE reinitialisation, jamais le mot de passe.
        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(1L), eq("USER_PASSWORD_RESET"), eq("USER"), eq(3L),
                isNull(), trace.capture(), isNull());
        assertThat(trace.getValue()).doesNotContain(reponse.password());
    }

    @Test
    void reset_deuxAppelsDeSuite_deuxMotsDePasse() {
        when(tokenService.verifyAccess("t")).thenReturn(claims(1L, "ADMIN"));
        AppUser cible = cible(3L, UserRole.ANALYST, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(cible));
        when(userRepository.save(cible)).thenReturn(cible);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$genere");

        String premier = service.resetPassword("Bearer t", 3L).password();
        String second = service.resetPassword("Bearer t", 3L).password();

        assertThat(premier).isNotEqualTo(second);
    }
}
