package tn.finrisk.alertcaseservice.domain.enums;

/** Domaine de alerts.severity (check ck_alerts_severity) — recopie locale,
 *  comme RiskLevel dans customer-service : chaque service possede ses enums. */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** La plus severe des deux — sert a l'AGREGATION (AlertGroupService). */
    public static Severity max(Severity a, Severity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
