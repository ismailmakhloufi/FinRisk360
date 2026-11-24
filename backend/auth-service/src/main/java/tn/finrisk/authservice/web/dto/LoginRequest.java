package tn.finrisk.authservice.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "username est obligatoire") String username,
        @NotBlank(message = "password est obligatoire") String password) {
}
