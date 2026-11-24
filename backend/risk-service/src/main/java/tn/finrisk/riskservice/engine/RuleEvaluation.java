package tn.finrisk.riskservice.engine;

import java.math.BigDecimal;
import tn.finrisk.riskservice.domain.RiskRule;

/**
 * Resultat de l'evaluation d'UNE regle :
 *  - factor        : 0, 0.5 ou 1 (notation graduee appliquee au poids)
 *  - contribution  : weight x factor, ce qui rentre dans la somme du score
 *  - reason        : phrase lisible pour l'analyste (rangee dans risk_factors.reason)
 *  - neutralized   : regle NON applicable faute de donnees (pas de baseline...) :
 *                    elle est quand meme stockee (factor 0) pour qu'on voie
 *                    POURQUOI elle n'a pas joue.
 */
public record RuleEvaluation(
        RiskRule rule,
        BigDecimal factor,
        BigDecimal observed,
        BigDecimal contribution,
        String reason,
        boolean neutralized) {

    /** Declenchee = apporte des points au score. */
    public boolean triggered() {
        return factor.signum() > 0;
    }

    /** A conserver dans risk_factors : declenchee OU neutralisee explicablement. */
    public boolean worthPersisting() {
        return triggered() || neutralized;
    }
}
