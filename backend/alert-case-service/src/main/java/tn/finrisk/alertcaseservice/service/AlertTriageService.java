package tn.finrisk.alertcaseservice.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tn.finrisk.alertcaseservice.client.AiClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.service.AlertTriageRule.Result;

/** Analyse consultative : aucune modification de statut ou de solde. */
@Service
public class AlertTriageService {
    private static final Logger log = LoggerFactory.getLogger(AlertTriageService.class);
    private final AlertService alerts;
    private final RiskClient risk;
    private final AiClient ai;

    public AlertTriageService(AlertService alerts, RiskClient risk, AiClient ai) {
        this.alerts = alerts;
        this.risk = risk;
        this.ai = ai;
    }

    public record Triage(Long alertId, Long transactionId, String riskLevel,
            String aiVerdict, Double aiConfidence, Result result) {
    }

    public Triage analyze(Long alertId) {
        Alert alert = alerts.get(alertId);
        // Une panne moteur est propagee : aucun score fiable ne peut etre invente.
        EvaluationView evaluation = risk.getEvaluationById(alert.getAssessmentId());
        AiAnalysisView analysis;
        try {
            analysis = ai.analyze(alertId);
        } catch (DownstreamUnavailableException | RemoteResourceNotFoundException unavailable) {
            log.warn("Avis IA indisponible pour l'alerte {} : {}", alertId, unavailable.getMessage());
            analysis = null;
        }
        String verdict = analysis == null ? null : analysis.verdict();
        return new Triage(alertId, evaluation.transactionId(), evaluation.riskLevel(),
                verdict, analysis == null ? null : analysis.confidence(),
                AlertTriageRule.classify(evaluation.riskLevel(), verdict));
    }
}
