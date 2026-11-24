package tn.finrisk.authservice.web.dto;

import jakarta.validation.constraints.Size;
import tn.finrisk.authservice.domain.UserRole;

/**
 * PATCH partiel : tout est optionnel, mais AU MOINS un champ doit etre
 * present (verifie en service -> 400 sinon). password n'est jamais renvoye
 * dans les reponses ni trace en audit.
 */
public record UserUpdateRequest(
        UserRole role,
        Boolean active,
        @Size(min = 8, max = 72, message = "password : 8 a 72 caracteres")
        String password) {
}
