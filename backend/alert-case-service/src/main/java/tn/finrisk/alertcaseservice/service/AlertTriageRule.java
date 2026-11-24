package tn.finrisk.alertcaseservice.service;

/**
 * Classification prudente d'une alerte : cette regle ne modifie ni la
 * transaction ni l'alerte. L'execution d'une validation eventuelle doit
 * appliquer ses propres controles de concurrence et de tracabilite.
 */
public final class AlertTriageRule {

    public enum Priority { NORMAL, HIGH, URGENT }

    public enum Recommendation { ELIGIBLE_FOR_AUTOMATION, HUMAN_REVIEW }

    public record Result(Priority priority, Recommendation recommendation, String reason) {
    }

    private AlertTriageRule() {
    }

    public static Result classify(String riskLevel, String aiVerdict) {
        if ("CRITICAL".equals(riskLevel)) {
            return new Result(Priority.URGENT, Recommendation.HUMAN_REVIEW,
                    "Risque critique : revue humaine urgente");
        }
        if ("HIGH".equals(riskLevel)) {
            return new Result(Priority.HIGH, Recommendation.HUMAN_REVIEW,
                    "Risque eleve : revue humaine prioritaire");
        }
        if (!"LOW".equals(riskLevel) && !"MEDIUM".equals(riskLevel)) {
            return new Result(Priority.HIGH, Recommendation.HUMAN_REVIEW,
                    "Niveau de risque inconnu : revue humaine");
        }
        if ("FRAUDE_SUSPECTE".equals(aiVerdict)) {
            return new Result(Priority.HIGH, Recommendation.HUMAN_REVIEW,
                    "Fraude suspectee par l'IA : revue humaine prioritaire");
        }
        if ("NORMAL".equals(aiVerdict)) {
            return new Result(Priority.NORMAL, Recommendation.ELIGIBLE_FOR_AUTOMATION,
                    "Risque faible ou moyen et avis IA normal : controles complementaires requis");
        }
        return new Result(Priority.NORMAL, Recommendation.HUMAN_REVIEW,
                "Avis IA incertain ou indisponible : revue humaine");
    }
}
