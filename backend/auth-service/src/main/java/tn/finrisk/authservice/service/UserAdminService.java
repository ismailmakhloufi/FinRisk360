package tn.finrisk.authservice.service;

import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.repository.AppUserRepository;
import tn.finrisk.authservice.web.Bearer;
import tn.finrisk.authservice.web.dto.PasswordResetResponse;
import tn.finrisk.authservice.web.dto.UserCreateRequest;
import tn.finrisk.authservice.web.dto.UserResponse;
import tn.finrisk.authservice.web.dto.UserUpdateRequest;
import tn.finrisk.authservice.web.error.BadRequestException;
import tn.finrisk.authservice.web.error.ConflictException;
import tn.finrisk.authservice.web.error.ForbiddenException;
import tn.finrisk.authservice.web.error.UserNotFoundException;

/**
 * Administration des comptes (GET/POST/PATCH /api/users) — garde ADMIN a
 * chaque entree : la route du gateway impose deja le role, ce controle
 * DOUBLE (gateway + service) est le point central de la soutenance M5 :
 * un bypass du gateway (appel direct :8087) n'ouvre rien.
 *
 * Pas de DELETE dur : la FK case_comments/audit_logs/users interdit la
 * suppression, on desactive (isActive=false) — d'ou deux gardes d'auto-
 * destruction pour qu'un admin ne puisse jamais s'eteindre la lumiere.
 */
@Service
public class UserAdminService {

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;

    public UserAdminService(AppUserRepository userRepository,
                            BCryptPasswordEncoder passwordEncoder,
                            TokenService tokenService,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(String authorizationHeader) {
        requireAdmin(authorizationHeader);
        return userRepository.findAllByOrderByUserIdAsc().stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(String authorizationHeader, Long userId) {
        requireAdmin(authorizationHeader);
        return UserResponse.from(findOr404(userId));
    }

    @Transactional
    public UserResponse create(String authorizationHeader, UserCreateRequest request) {
        long adminId = requireAdmin(authorizationHeader);

        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("Nom d'utilisateur deja pris : " + request.username());
        }
        if (request.email() != null && !request.email().isBlank()
                && userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Adresse e-mail deja utilisee : " + request.email());
        }

        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setEmail(blankToNull(request.email()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setActive(true);
        AppUser saved = userRepository.save(user);

        auditService.record(adminId, "USER_CREATED", "USER", saved.getUserId(), null,
                "username=" + saved.getUsername() + ", role=" + saved.getRole(), null);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse update(String authorizationHeader, Long userId, UserUpdateRequest request) {
        long adminId = requireAdmin(authorizationHeader);

        if (request.role() == null && request.active() == null && request.password() == null) {
            throw new BadRequestException("Aucune modification fournie (role, active ou password)");
        }

        AppUser user = findOr404(userId);

        boolean desactive = Boolean.FALSE.equals(request.active());
        if (desactive && user.getUserId() == adminId) {
            throw new ConflictException("Impossible de desactiver son propre compte");
        }
        if (request.role() != null && user.getUserId() == adminId
                && request.role() != user.getRole()) {
            throw new ConflictException("Impossible de modifier son propre role");
        }

        String avant = "role=" + user.getRole() + ", active=" + user.isActive();

        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        AppUser saved = userRepository.save(user);

        String apres = "role=" + saved.getRole() + ", active=" + saved.isActive()
                + (request.password() != null ? ", mot de passe reinitialise" : "");
        auditService.record(adminId, "USER_UPDATED", "USER", saved.getUserId(), avant, apres, null);
        return UserResponse.from(saved);
    }

    /**
     * Reinitialisation SUR GENERATION (probleme 6) : l'analyste a oublie son
     * mot de passe, l'admin le lui rend.
     *
     * Contrairement a update(...password), le serveur choisit le mot de passe
     * et le REND EN CLAIR une seule fois : c'est le seul cas ou un clair
     * quitte ce service. La base n'en gardera que le hash BCrypt et l'audit
     * n'en retient qu'un resume ("mot de passe reinitialise par l'admin").
     */
    @Transactional
    public PasswordResetResponse resetPassword(String authorizationHeader, Long userId) {
        long adminId = requireAdmin(authorizationHeader);
        AppUser user = findOr404(userId);

        String plain = PasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(plain));
        userRepository.save(user);

        auditService.record(adminId, "USER_PASSWORD_RESET", "USER", user.getUserId(),
                null, "mot de passe reinitialise par l'admin", null);
        return new PasswordResetResponse(user.getUserId(), user.getUsername(), plain);
    }

    /**
     * Double verrou M5 : meme appele HORS gateway (bypass direct 8087),
     * l'API d'administration exige un access-token signe portant rol=ADMIN.
     */
    private long requireAdmin(String authorizationHeader) {
        JsonNode claims = tokenService.verifyAccess(Bearer.parse(authorizationHeader));
        if (!"ADMIN".equals(claims.path("rol").asText())) {
            throw new ForbiddenException("Acces reserve aux administrateurs (role ADMIN requis)");
        }
        return claims.path("sub").asLong();
    }

    private AppUser findOr404(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
