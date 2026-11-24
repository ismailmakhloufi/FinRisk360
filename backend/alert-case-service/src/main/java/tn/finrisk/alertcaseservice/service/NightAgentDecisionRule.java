package tn.finrisk.alertcaseservice.service;

import tn.finrisk.alertcaseservice.domain.enums.AgentAction;

/**
 * LA MATRICE de l'agent nocturne — design HYBRIDE : le verdict du moteur
 * (règles, score, explicable) COMBINE a l'avis de l'IA (insight engine).
 * Pas de decision prise « a l'aveugle » par un seul des deux : ils doivent
 * s'entendre pour que l'agent ose valider tout seul.
 *
 *   CRITICAL (>=85)              -> toujours LAISSE (jamais valide la nuit)
 *   IA = FRAUDE_SUSPECTE         -> LAISSE (l'IA signale, meme sur un LOW)
 *   IA indisponible              -> LAISSE (sans deuxieme avis, pas d'ecriture)
 *   LOW/MEDIUM + IA = NORMAL     -> VALIDE (seul cas d'ecriture automatique)
 *   LOW/MEDIUM + IA = INCERTAIN  -> LAISSE (l'IA hesite)
 *   HIGH + IA = NORMAL           -> LAISSE (divergence : cas prioritaire le matin)
 *   HIGH + IA = INCERTAIN        -> LAISSE (les deux voient du risque)
 *
 * PUR : aucune dependance (HTTP, BDD, horloge) — la matrice se teste seule.
 */
public final class NightAgentDecisionRule {

    /** Ce que l'agent decide, avec le motif lisible au rapport du matin. */
    public record Outcome(AgentAction action, String reason) {
    }

    private NightAgentDecisionRule() {
    }

    /**
     * @param riskLevel  verdict du moteur : LOW / MEDIUM / HIGH / CRITICAL
     * @param aiVerdict  avis de l'IA : NORMAL / INCERTAIN / FRAUDE_SUSPECTE,
     *                   ou null si l'analyse a echoue
     * @param confidence confiance de l'IA (0..1) ou null
     */
    public static Outcome decide(String riskLevel, String aiVerdict, Double confidence) {
        String conf = confidence == null ? "?" : Math.round(confidence * 100) + "%";

        if ("CRITICAL".equals(riskLevel)) {
            return new Outcome(AgentAction.LEFT_PENDING,
                    "CRITICAL (score >= 85) : reserve a l'analyste, jamais valide la nuit");
        }
        if (aiVerdict == null) {
            return new Outcome(AgentAction.LEFT_PENDING,
                    "IA indisponible : sans deuxieme avis, laissee a l'analyste");
        }
        if ("FRAUDE_SUSPECTE".equals(aiVerdict)) {
            return new Outcome(AgentAction.LEFT_PENDING,
                    "IA : fraude plausible (confiance " + conf + ") : laissee a l'analyste");
        }

        boolean engineOk = "LOW".equals(riskLevel) || "MEDIUM".equals(riskLevel);
        if (engineOk && "NORMAL".equals(aiVerdict)) {
            return new Outcome(AgentAction.VALIDATED,
                    "Moteur " + riskLevel + " + IA NORMAL (confiance " + conf
                            + ") : validee avec surveillance");
        }
        if (engineOk) {
            return new Outcome(AgentAction.LEFT_PENDING,
                    "IA hesite (confiance " + conf + ") : laissee a l'analyste");
        }
        if ("NORMAL".equals(aiVerdict)) {
            return new Outcome(AgentAction.LEFT_PENDING,
                    "Divergence : moteur " + riskLevel + " mais IA NORMAL (confiance "
                            + conf + ") : revue humaine prioritaire");
        }
        return new Outcome(AgentAction.LEFT_PENDING,
                "Moteur " + riskLevel + " + IA INCERTAIN (confiance " + conf
                        + ") : laissee a l'analyste");
    }
}
