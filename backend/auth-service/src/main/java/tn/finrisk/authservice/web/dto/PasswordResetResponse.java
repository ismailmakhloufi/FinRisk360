package tn.finrisk.authservice.web.dto;

/**
 * Reponse de POST /api/users/{id}/password.
 *
 * C'est le SEUL endroit de toute l'application ou un mot de passe apparait en
 * clair : il n'est ni stocke (la base n'a que le hash BCrypt) ni journalise
 * (audit_logs n'enregistre qu'un resume de la reinitialisation). Il est donc
 * rendu UNE FOIS, a l'admin qui vient de declencher la reinitialisation, a
 * transmettre a l'utilisateur concerne.
 */
public record PasswordResetResponse(
        Long id,
        String username,
        String password) {
}
