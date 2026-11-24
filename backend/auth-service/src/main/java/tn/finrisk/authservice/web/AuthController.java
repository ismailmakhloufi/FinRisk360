package tn.finrisk.authservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.authservice.service.AuthService;
import tn.finrisk.authservice.web.dto.LoginRequest;
import tn.finrisk.authservice.web.dto.RefreshRequest;
import tn.finrisk.authservice.web.dto.TokenResponse;
import tn.finrisk.authservice.web.dto.UserProfile;

/**
 * Les 3 operations publiques de l'auth-service — aucune protection ici :
 * login/refresh sont PUBLICS par definition, /me se protege lui-meme
 * (garde interne, defence en profondeur). Le reste du monde, c'est le
 * gateway qui le tri (M5 §7).
 */
@Tag(name = "Authentification", description = "M5 : login, rafraichissement, profil")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /api/auth/login { "username": "admin", "password": "..." } */
    @Operation(summary = "Connexion",
            description = "Retourne access + refresh token. Identifiants fautifs -> 401 "
                    + "avec un message unique (anti-enumeration) ; compte desactive -> 403.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paire de tokens + profil"),
            @ApiResponse(responseCode = "400", description = "Corps manquant ou incomplet"),
            @ApiResponse(responseCode = "401", description = "Identifiants invalides"),
            @ApiResponse(responseCode = "403", description = "Compte desactive")})
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               @RequestHeader(value = "X-Forwarded-For", required = false)
                               String ipAddress) {
        return authService.login(request, ipAddress);
    }

    /** POST /api/auth/refresh { "refreshToken": "..." } */
    @Operation(summary = "Rafraichir les tokens",
            description = "Un refresh n'obtient de droits que si son compte est toujours actif.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nouvelle paire de tokens"),
            @ApiResponse(responseCode = "400", description = "refreshToken manquant"),
            @ApiResponse(responseCode = "401", description = "Refresh invalide ou expire"),
            @ApiResponse(responseCode = "403", description = "Compte desactive")})
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request,
                                 @RequestHeader(value = "X-Forwarded-For", required = false)
                                 String ipAddress) {
        return authService.refresh(request, ipAddress);
    }

    /** GET /api/auth/me (Authorization: Bearer <access>) */
    @Operation(summary = "Profil de l'utilisateur connecte",
            description = "Verifie le token ENCORE ici (independamment du gateway) puis "
                    + "rejoue l'etat du compte depuis la base.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le profil"),
            @ApiResponse(responseCode = "401", description = "Token absent, invalide ou expire"),
            @ApiResponse(responseCode = "403", description = "Compte desactive")})
    @GetMapping("/me")
    public UserProfile me(@RequestHeader(value = "Authorization", required = false)
                          String authorization) {
        return authService.me(authorization);
    }
}
