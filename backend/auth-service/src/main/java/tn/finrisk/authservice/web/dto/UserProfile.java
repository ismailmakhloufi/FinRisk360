package tn.finrisk.authservice.web.dto;

public record UserProfile(
        Long id,
        String username,
        String email,
        tn.finrisk.authservice.domain.UserRole role,
        boolean active) {

    public static UserProfile from(tn.finrisk.authservice.domain.AppUser user) {
        return new UserProfile(user.getUserId(), user.getUsername(), user.getEmail(),
                user.getRole(), user.isActive());
    }
}
