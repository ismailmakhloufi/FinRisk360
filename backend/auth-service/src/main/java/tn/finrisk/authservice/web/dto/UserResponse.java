package tn.finrisk.authservice.web.dto;

import java.time.OffsetDateTime;
import tn.finrisk.authservice.domain.AppUser;
import tn.finrisk.authservice.domain.UserRole;

/** Jamais passwordHash ici : les mots de passe ne sortent jamais du service. */
public record UserResponse(
        Long id,
        String username,
        String email,
        UserRole role,
        boolean active,
        OffsetDateTime createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getUserId(), user.getUsername(), user.getEmail(),
                user.getRole(), user.isActive(), user.getCreatedAt());
    }
}
