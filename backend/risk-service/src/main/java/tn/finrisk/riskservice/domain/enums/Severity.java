package tn.finrisk.riskservice.domain.enums;

/**
 * Gravite d'une regle (check SQL ck_rr_severity).
 * Ceci sert a classer/regler les regles, PAS a noter une transaction :
 * pour ca il y a RiskLevel.
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
