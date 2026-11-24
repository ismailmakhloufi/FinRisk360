package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tn.finrisk.alertcaseservice.client.AiClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.service.AlertTriageRule.Priority;
import tn.finrisk.alertcaseservice.service.AlertTriageRule.Recommendation;

class AlertTriageServiceTest {
    private final AlertService alerts = mock(AlertService.class);
    private final RiskClient risk = mock(RiskClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final AlertTriageService service = new AlertTriageService(alerts, risk, ai);

    private void assessment(String level) {
        Alert alert = new Alert();
        alert.setAssessmentId(7L);
        when(alerts.get(4L)).thenReturn(alert);
        when(risk.getEvaluationById(7L)).thenReturn(new EvaluationView(
                7L, 9L, BigDecimal.TEN, level, "MONITORING", OffsetDateTime.now(), "v1"));
    }

    @Test
    void risqueEleveRestePrioritaireMemeSiIaNormale() {
        assessment("HIGH");
        when(ai.analyze(4L)).thenReturn(new AiAnalysisView(4L, "NORMAL", 0.9, "ok"));
        var triage = service.analyze(4L);
        assertThat(triage.transactionId()).isEqualTo(9L);
        assertThat(triage.result().priority()).isEqualTo(Priority.HIGH);
        assertThat(triage.result().recommendation()).isEqualTo(Recommendation.HUMAN_REVIEW);
    }

    @Test
    void iaIndisponibleNePermetPasLAutomatisation() {
        assessment("LOW");
        when(ai.analyze(4L)).thenThrow(new DownstreamUnavailableException(
                "ai-assistant-service", new IllegalStateException("indisponible")));
        var triage = service.analyze(4L);
        assertThat(triage.aiVerdict()).isNull();
        assertThat(triage.result().recommendation()).isEqualTo(Recommendation.HUMAN_REVIEW);
    }

    @Test
    void risqueIndisponibleNeProduitPasDeDecision() {
        Alert alert = new Alert();
        alert.setAssessmentId(7L);
        when(alerts.get(4L)).thenReturn(alert);
        when(risk.getEvaluationById(7L)).thenThrow(new IllegalStateException("indisponible"));
        assertThatThrownBy(() -> service.analyze(4L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ai);
    }
}
