package tn.finrisk.riskservice.web.error;

/** Aucun risk_assessment ne correspond (par id ou par transaction). HTTP 404. */
public class AssessmentNotFoundException extends RuntimeException {

    public AssessmentNotFoundException(String key, Long value) {
        super("Evaluation introuvable : " + key + " = " + value);
    }
}
