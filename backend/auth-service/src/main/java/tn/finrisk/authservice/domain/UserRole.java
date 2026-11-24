package tn.finrisk.authservice.domain;

/**
 * Domaine de users.role (check ck_users_role) — les roles du RBAC (§18).
 * NB : ce ne sont PAS des niveaux hiérarchiques mais des profils metier
 * paralleles (l'architecture §5) — le maillage precis vit dans la table de
 * routes du gateway (read-roles / write-roles par chemin).
 */
public enum UserRole {
    ADMIN,
    MANAGER,
    ANALYST,
    BANK_SIMULATOR
}
