package tn.finrisk.alertcaseservice.web.error;

/** Aucune case ne correspond (par id). HTTP 404. */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String key, Long value) {
        super("Case introuvable : " + key + " = " + value);
    }
}
