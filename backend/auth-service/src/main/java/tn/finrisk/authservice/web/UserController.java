package tn.finrisk.authservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.authservice.service.UserAdminService;
import tn.finrisk.authservice.web.dto.PasswordResetResponse;
import tn.finrisk.authservice.web.dto.UserCreateRequest;
import tn.finrisk.authservice.web.dto.UserResponse;
import tn.finrisk.authservice.web.dto.UserUpdateRequest;

/**
 * Administration des comptes — exclusivement role ADMIN, verifie DEUX fois :
 * une par le gateway (routes["/api/users*"].write-roles=[ADMIN]) et une par
 * le service lui-meme. Appel direct sur :8087 sans passer par le gateway,
 * meme avec un token ANALYST valide -> 403.
 */
@Tag(name = "Utilisateurs", description = "M5 : gestion des comptes (role ADMIN)")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserAdminService userAdminService;

    public UserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /** GET /api/users */
    @Operation(summary = "Lister les comptes", description = "ADMIN requis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La liste"),
            @ApiResponse(responseCode = "401", description = "Token absent ou invalide"),
            @ApiResponse(responseCode = "403", description = "Role different de ADMIN")})
    @GetMapping
    public List<UserResponse> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userAdminService.list(authorization);
    }

    /** GET /api/users/{id} */
    @Operation(summary = "Consulter un compte", description = "ADMIN requis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Le compte"),
            @ApiResponse(responseCode = "401", description = "Token absent ou invalide"),
            @ApiResponse(responseCode = "403", description = "Role different de ADMIN"),
            @ApiResponse(responseCode = "404", description = "Utilisateur inconnu")})
    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id,
                            @RequestHeader(value = "Authorization", required = false)
                            String authorization) {
        return userAdminService.get(authorization, id);
    }

    /** POST /api/users { username, email?, password, role } */
    @Operation(summary = "Creer un compte",
            description = "ADMIN requis ; username/email uniques (409) ; "
                    + "le mot de passe est hashé en BCrypt, jamais stocke en clair.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Compte cree (header Location)"),
            @ApiResponse(responseCode = "400", description = "Corps invalide"),
            @ApiResponse(responseCode = "401", description = "Token absent ou invalide"),
            @ApiResponse(responseCode = "403", description = "Role different de ADMIN"),
            @ApiResponse(responseCode = "409", description = "Username ou email deja pris")})
    @PostMapping
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserResponse created = userAdminService.create(authorization, request);
        return ResponseEntity
                .created(URI.create("/api/users/" + created.id()))
                .body(created);
    }

    /** POST /api/users/{id}/password */
    @Operation(summary = "Reinitialiser le mot de passe d'un compte",
            description = "ADMIN requis ; le serveur GENERE un mot de passe fort "
                    + "et le renvoie en clair UNE SEULE FOIS — il n'est ni stocke "
                    + "ni journalise. Cas « l'analyste a oublie son mot de passe, "
                    + "l'admin le lui rend ».")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mot de passe provisoire (trace USER_PASSWORD_RESET)"),
            @ApiResponse(responseCode = "401", description = "Token absent ou invalide"),
            @ApiResponse(responseCode = "403", description = "Role different de ADMIN"),
            @ApiResponse(responseCode = "404", description = "Utilisateur inconnu")})
    @PostMapping("/{id}/password")
    public PasswordResetResponse resetPassword(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userAdminService.resetPassword(authorization, id);
    }

    /** PATCH /api/users/{id} { role?, active?, password? } */
    @Operation(summary = "Modifier un compte",
            description = "ADMIN requis ; au moins un champ (400 sinon) ; "
                    + "l'admin ne peut ni se desactiver ni changer son propre role (409).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compte mis a jour (trace en audit_logs)"),
            @ApiResponse(responseCode = "400", description = "Aucun champ fourni"),
            @ApiResponse(responseCode = "401", description = "Token absent ou invalide"),
            @ApiResponse(responseCode = "403", description = "Role different de ADMIN"),
            @ApiResponse(responseCode = "404", description = "Utilisateur inconnu"),
            @ApiResponse(responseCode = "409", description = "Auto-destruction refusee")})
    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable Long id,
                               @Valid @RequestBody UserUpdateRequest request,
                               @RequestHeader(value = "Authorization", required = false)
                               String authorization) {
        return userAdminService.update(authorization, id, request);
    }
}
