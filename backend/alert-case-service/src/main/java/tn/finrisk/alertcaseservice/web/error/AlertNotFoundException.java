package tn.finrisk.alertcaseservice.web.error;

/** Aucune alerte ne correspond (par id). HTTP 404. */
public class AlertNotFoundException extends RuntimeException {

    public AlertNotFoundException(String key, Long value) {
        super("Alerte introuvable : " + key + " = " + value);
    }
}
