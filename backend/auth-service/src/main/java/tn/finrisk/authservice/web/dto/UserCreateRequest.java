package tn.finrisk.authservice.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.finrisk.authservice.domain.UserRole;

public record UserCreateRequest(
        @NotBlank(message = "username est obligatoire")
        @Size(max = 50, message = "username : 50 caracteres max")
        String username,

        @Email(message = "email doit etre une adresse valide")
        @Size(max = 100, message = "email : 100 caracteres max")
        String email,

        @NotBlank(message = "password est obligatoire")
        @Size(min = 8, max = 72, message = "password : 8 a 72 caracteres")
        String password,

        @NotNull(message = "role est obligatoire (ADMIN, MANAGER ou ANALYST)")
        UserRole role) {
}
