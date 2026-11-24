package tn.finrisk.aiservice.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.FactorView;
import tn.finrisk.aiservice.web.dto.AiVerdict;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Traduction des signaux en AVIS : verdict, confiance, explication.
 * PUR comme l'InsightEngine — testable sans rien appeler.
 *
 * Règles délibérément simples et lisibles (le jury doit pouvoir les
 * répéter) :
 *   - >= 1 signal HIGH         -> FRAUDE_SUSPECTE
 *   - >= 2 signaux MEDIUM      -> INCERTAIN
 *   - sinon                    -> NORMAL
 *
 * Confiance : 0.4 de base (jamais sûr de rien), +0.2 par signal HIGH,
 * +0.1 par MEDIUM, plafonnée à 0.95 ; l'INSUFFICIENT_DATA la borne à 0.5 —
 * on ne peut pas être « très confiant » sans données.
 */
public final class AiVerdictEngine {

    static final double BASE_CONFIDENCE = 0.4;
    static final double HIGH_BOOST = 0.2;
    static final double MEDIUM_BOOST = 0.1;
    static final double MAX_CONFIDENCE = 0.95;
    static final double MAX_WITHOUT_DATA = 0.5;

    private AiVerdictEngine() {
    }

    public static AiVerdict verdict(long highCount, long mediumCount) {
        if (highCount >= 1) {
            return AiVerdict.FRAUDE_SUSPECTE;
        }
        if (mediumCount >= 2) {
            return AiVerdict.INCERTAIN;
        }
        return AiVerdict.NORMAL;
    }

    public static double confidence(long highCount, long mediumCount, boolean insufficientData) {
        double raw = BASE_CONFIDENCE + highCount * HIGH_BOOST + mediumCount * MEDIUM_BOOST;
        double capped = Math.min(raw, MAX_CONFIDENCE);
        if (insufficientData) {
            capped = Math.min(capped, MAX_WITHOUT_DATA);
        }
        return Math.round(capped * 100.0) / 100.0;
    }

    /**
     * L'explication : LE score + ses facteurs, PUIS ce que l'IA ajoute,
     * PUIS le verdict — phrase unique, tout branché sur une donnée réelle.
     */
    public static String explain(EvaluationView evaluation, List<Insight> insights,
            AiVerdict verdict, double confidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("Score moteur ");
        sb.append(evaluation.riskScore() == null ? "?" : plain(evaluation.riskScore()));
        sb.append("/100 → ").append(evaluation.decision() == null ? "?" : evaluation.decision());

        List<FactorView> top = evaluation.factorsOrEmpty().stream()
                .filter(f -> f.factorScore() != null && f.factorScore().signum() > 0)
                .sorted(Comparator.comparing(FactorView::factorScore).reversed())
                .limit(3)
                .toList();
        if (!top.isEmpty()) {
            sb.append(" (facteurs : ");
            sb.append(top.stream()
                    .map(f -> f.ruleName() + " " + plain(f.factorScore()))
                    .collect(Collectors.joining(", ")));
            sb.append(")");
        }

        List<String> signals = insights.stream()
                .filter(i -> i.code() != InsightCode.INSUFFICIENT_DATA)
                .limit(3)
                .map(Insight::message)
                .toList();
        if (signals.isEmpty()) {
            sb.append(". L'IA n'a trouvé aucun signal complémentaire au-delà des règles.");
        } else {
            sb.append(". L'IA détecte en plus : ").append(String.join(" ", signals));
        }

        sb.append(". Avis IA : ").append(verdict)
                .append(" (confiance ").append(fmt2(confidence)).append(")");
        return sb.toString();
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
