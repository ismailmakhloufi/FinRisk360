package tn.finrisk.riskservice.domain.enums;

/**
 * Decision de sortie du moteur (check SQL ck_ra_decision).
 * APPROVED       : score 0-29   , transaction banale, on passe
 * MONITORING     : score 30-59  , atypique, on note sans agir
 * PENDING_REVIEW : score 60-84  , un humain doit la voir
 * HOLD           : score 85-100 , forte suspicion, blocage recommande
 */
public enum Decision {
    APPROVED,
    MONITORING,
    PENDING_REVIEW,
    HOLD
}
