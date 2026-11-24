package tn.finrisk.alertcaseservice.domain.enums;

/**
 * Domaine de alerts.alert_type (check ck_alerts_type).
 * Corresponde aux grandes familles de signaux du moteur de risque.
 */
public enum AlertType {
    HIGH_AMOUNT,
    VELOCITY,
    GEOGRAPHIC,
    BEHAVIOR,
    DUPLICATE,
    OTHER
}
