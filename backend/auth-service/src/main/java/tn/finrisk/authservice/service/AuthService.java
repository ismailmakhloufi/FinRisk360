package tn.finrisk.authservice.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.repository.AppUserRepository;
import tn.finrisk.authservice.security.JwtException;
import tn.finrisk.authservice.web.Bearer;
import tn.finrisk.authservice.web.dto.LoginRequest;
import tn.finrisk.authservice.web.dto.RefreshRequest;
import tn.finrisk.authservice.web.dto.TokenResponse;
import tn.finrisk.authservice.web.dto.UserProfile;
import tn.finrisk.authservice.web.error.AuthenticationFailedException;
import tn.finrisk.authservice.web.error.ForbiddenException;

/**
 * Les trois operations publiques de l'authentification (M5) :
 *   POST /api/auth/login    mot de passe -> paire de tokens
 *   POST /api/auth/refresh  refresh -> nouveau paire (rotation)
 *   GET  /api/auth/me       access -> profil (garde de defense en profondeur)
 *
 * Defence en profondeur : meme si le gateway arme deja le 401/403, ce
 * service re-verifie SON token sur /me — il n'a pas besoin de lui faire
 * confiance pour proteger ses propres donnees.
 */
@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;

    public AuthService(AppUserRepository userRepository,
                       BCryptPasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    @Transactional
    public TokenResponse login(LoginRequest request, String ipAddress) {
        AppUser user = userRepository.findByUsername(request.username()).orElse(null);

        if (user == null) {
            auditService.recordRefus(null, "LOGIN_FAILED", "USER", 0L, null,
                    "username=" + request.username() + " (compte inexistant)", ipAddress);
            throw new AuthenticationFailedException("Identifiants invalides");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.recordRefus(user.getUserId(), "LOGIN_FAILED", "USER", user.getUserId(),
                    null, "Mot de passe incorrect", ipAddress);
            throw new AuthenticationFailedException("Identifiants invalides");
        }
        if (!user.isActive()) {
            auditService.recordRefus(user.getUserId(), "LOGIN_FAILED", "USER", user.getUserId(),
                    "active=true", "Compte desactive", ipAddress);
            throw new ForbiddenException("Compte desactive");
        }

        auditService.record(user.getUserId(), "LOGIN_SUCCESS", "USER", user.getUserId(),
                null, "role=" + user.getRole(), ipAddress);
        return respond(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request, String ipAddress) {
        JsonNode claims = tokenService.verifyRefresh(request.refreshToken());
        long userId = claims.path("sub").asLong();
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailedException("Token de rafraichissement invalide"));
        if (!user.isActive()) {
            throw new ForbiddenException("Compte desactive");
        }
        return respond(user);
    }

    @Transactional(readOnly = true)
    public UserProfile me(String authorizationHeader) {
        JsonNode claims = tokenService.verifyAccess(Bearer.parse(authorizationHeader));
        AppUser user = userRepository.findById(claims.path("sub").asLong())
                .orElseThrow(() -> new AuthenticationFailedException("Token invalide"));
        if (!user.isActive()) {
            throw new ForbiddenException("Compte desactive");
        }
        return UserProfile.from(user);
    }

    private TokenResponse respond(AppUser user) {
        TokenService.TokenPair pair = tokenService.issuePair(user);
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), UserProfile.from(user));
    }
}
