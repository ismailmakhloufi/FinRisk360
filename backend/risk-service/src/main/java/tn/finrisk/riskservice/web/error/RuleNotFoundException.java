package tn.finrisk.riskservice.web.error;

/** Regle demandee absente de risk_rules. HTTP 404. */
public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(Long ruleId) {
        super("Regle introuvable : id = " + ruleId);
    }
}
