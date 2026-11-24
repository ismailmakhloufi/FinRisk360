package tn.finrisk.riskservice.domain.enums;

/**
 * Niveau de risque (domaine LOW..CRITICAL, cf. checks SQL ck_ra_level et ck_rp_level).
 * Utilise a la fois par risk_assessments.risk_level et risk_profiles.profile_risk_level :
 * les deux colonnes partagent exactement le meme domaine, donc UNE SEULE enum
 * (une divergence entre les deux arriverait forcement sinon).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
