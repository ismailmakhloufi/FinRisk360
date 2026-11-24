package tn.finrisk.riskservice.engine;

/**
 * Aucune regle activee dans risk_rules : le scoreur ne peut pas travailler.
 * Le pire bug possible serait de renvoyer 0 (tout approuve) silencieusement :
 * on echec BRUYAMMENT (mappe en 500 par le GlobalExceptionHandler).
 */
public class NoEnabledRulesException extends RuntimeException {

    public NoEnabledRulesException() {
        super("Aucune regle activee dans risk_rules : seed non applique ou toutes desactivees.");
    }
}
