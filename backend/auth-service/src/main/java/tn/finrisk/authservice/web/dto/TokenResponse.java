package tn.finrisk.authservice.web.dto;

/** Paire de tokens + profil rendu par login/refresh. */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserProfile user) {
}
