package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.NightAgentDecisionRepository;
import tn.finrisk.alertcaseservice.service.NightAgentDecisionRule.Outcome;

/**
 * Unite de NightAgentWorker : les effets de bord d'une decision.
 * VALIDATED = completion + cloture legale de l'alerte + ligne + audit ;
 * LEFT_PENDING = ligne + audit seulement (jamais de completion).
 */
@ExtendWith(MockitoExtension.class)
class NightAgentWorkerTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");

    @Mock
    private TransactionClient transactionClient;
    @Mock
    private AlertService alertService;
    @Mock
    private NightAgentDecisionRepository repository;
    @Mock
    private AuditService auditService;

    @Mock
    private NightAgentIntentStore intents;

    private NightAgentWorker worker;

    @Test
    void validationSuspendue_refuseEcritureDistante() {
        ReflectionTestUtils.setField(worker, "autoValidationEnabled", false);
        assertThatThrownBy(() -> worker.apply(tx(), eval(), alert(AlertStatus.OPEN),
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"),
                NightAgentDecisionRule.decide("LOW", "NORMAL", 0.9)))
                .isInstanceOf(IllegalStateException.class);
        verify(transactionClient, never()).completeNightIntent(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(repository, never()).save(any());
    }

    @BeforeEach
    void setUp() {
        worker = new NightAgentWorker(transactionClient, alertService, repository, auditService, intents);
        org.mockito.Mockito.lenient().when(intents.prepare(any())).thenAnswer(call -> call.getArgument(0));
        org.mockito.Mockito.lenient().when(intents.finalizeCompleted(any())).thenReturn(true);
        ReflectionTestUtils.setField(worker, "autoValidationEnabled", true);
    }

    // --------------------------------------------------------------- OUTILS

    private TransactionView tx() {
        return new TransactionView(7L, 1L, new BigDecimal("500.00"), "TND",
                "TRANSFER", T, "bené", null, "PENDING");
    }

    private EvaluationView eval() {
        return new EvaluationView(43L, 7L, new BigDecimal("20.00"),
                "LOW", "MONITORING", T, "v1");
    }

    private Alert alert(AlertStatus status) {
        Alert alert = new Alert();
        alert.setAlertId(9L);
        alert.setAssessmentId(43L);
        alert.setAlertCode("ALT-43");
        alert.setAlertType(AlertType.OTHER);
        alert.setSeverity(Severity.LOW);
        alert.setStatus(status);
        alert.setCreatedAt(T);
        return alert;
    }

    // ------------------------------------------------------------ VALIDEE

    @Test
    void validee_completeClotureLaChaineEtTrace() {
        Alert open = alert(AlertStatus.OPEN);
        when(alertService.changeStatus(any(), any(), any(), any())).thenAnswer(inv -> {
            Alert maj = alert(AlertStatus.OPEN);
            maj.setStatus(inv.getArgument(1));
            return maj;
        });
        when(repository.save(any(NightAgentDecision.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Outcome outcome = NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90);
        NightAgentDecision row = worker.apply(tx(), eval(), open,
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"), outcome);

        assertThat(row.getAgentAction()).isEqualTo(AgentAction.VALIDATED);
        assertThat(row.getTransactionId()).isEqualTo(7L);
        assertThat(row.getAlertId()).isEqualTo(9L);
        assertThat(row.getAiVerdict()).isEqualTo("NORMAL");
        assertThat(row.getAgentReason()).contains("validee");
        assertThat(row.getRunAt()).isNotNull();

        verify(transactionClient).completeNightIntent(eq(7L), any());
        // Chaine legale : OPEN -> ACKNOWLEDGED -> IN_PROGRESS -> RESOLVED.
        verify(alertService).changeStatus(9L, AlertStatus.ACKNOWLEDGED, null, null);
        verify(alertService).changeStatus(9L, AlertStatus.IN_PROGRESS, null, null);
        verify(alertService).changeStatus(9L, AlertStatus.RESOLVED, null, null);
        verify(auditService).record(isNull(), eq("NIGHT_AGENT_VALIDATED"),
                eq("TRANSACTION"), eq(7L), eq("PENDING"), eq("VALIDATED"), isNull());
    }

    // ------------------------------------------------- REPONSE PERDUE

    @Test
    void validationReponsePerdue_etatReelConfirme_quandMemeTracee() {
        when(repository.save(any(NightAgentDecision.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new DownstreamUnavailableException(
                "transaction-service", new RuntimeException("timeout")))
                .when(transactionClient).completeNightIntent(eq(7L), any());
        when(intents.completed(any())).thenReturn(false, true);
        // Transaction-service A bien applique la validation, seule la
        // reponse s'est perdue : la decision doit etre portee au rapport.
        NightAgentDecision row = worker.apply(tx(), eval(), alert(AlertStatus.RESOLVED),
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"),
                NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90));

        assertThat(row.getAgentAction()).isEqualTo(AgentAction.VALIDATED);
        verify(repository).save(any(NightAgentDecision.class));
        verify(auditService).record(isNull(), eq("NIGHT_AGENT_VALIDATED"),
                eq("TRANSACTION"), eq(7L), eq("PENDING"), eq("VALIDATED"), isNull());
    }

    @Test
    void validationReponsePerdue_nonAppliquee_rejoueAuBalayageSuivant() {
        org.mockito.Mockito.doThrow(new DownstreamUnavailableException(
                "transaction-service", new RuntimeException("timeout")))
                .when(transactionClient).completeNightIntent(eq(7L), any());
        assertThatThrownBy(() -> worker.apply(tx(), eval(), alert(AlertStatus.OPEN),
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"),
                NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90)))
                .isInstanceOf(DownstreamUnavailableException.class);

        // Pas de decision fausse : ni ligne, ni cloture d'alerte, ni audit —
        // la transaction reste en file et le balayage suivant rejoue.
        verify(repository, never()).save(any());
        verify(alertService, never()).changeStatus(any(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void validationEtVerificationEchouees_rejoueAussi() {
        org.mockito.Mockito.doThrow(new DownstreamUnavailableException(
                "transaction-service", new RuntimeException("timeout")))
                .when(transactionClient).completeNightIntent(eq(7L), any());
        when(intents.completed(any())).thenReturn(false).thenThrow(new DownstreamUnavailableException(
                "transaction-service", new RuntimeException("verification indisponible")));
        assertThatThrownBy(() -> worker.apply(tx(), eval(), alert(AlertStatus.OPEN),
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"),
                NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90)))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void recoveryFinalizesAnExistingReceiptWithoutMovingMoneyAgain() {
        var intent = new NightAgentIntentStore.Intent(7L, java.util.UUID.randomUUID(), 43L, 9L,
                new BigDecimal("500.00"), new BigDecimal("20.00"), "LOW", "MONITORING",
                "NORMAL", 0.9, "Motif conserve", "PREPARED");
        when(intents.completed(intent)).thenReturn(true);
        when(alertService.get(9L)).thenReturn(alert(AlertStatus.RESOLVED));

        worker.recover(intent);

        verify(transactionClient, never()).completeNightIntent(eq(7L), any());
        verify(intents).finalizeCompleted(intent);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(row ->
                row.getAgentAction() == AgentAction.VALIDATED
                        && "Motif conserve".equals(row.getAgentReason())));
    }

    // --------------------------------------------------------- LAISSEE

    @Test
    void laissee_neCompleteRienMaisTrace() {
        when(repository.save(any(NightAgentDecision.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Outcome outcome = NightAgentDecisionRule.decide("HIGH", "NORMAL", 0.85);
        NightAgentDecision row = worker.apply(tx(), eval(), alert(AlertStatus.OPEN),
                new AiAnalysisView(9L, "NORMAL", 0.85, "ok"), outcome);

        assertThat(row.getAgentAction()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(row.getAgentReason()).contains("Divergence");

        verify(transactionClient, never()).completeNightIntent(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(alertService, never()).changeStatus(any(), any(), any(), any());
        verify(auditService).record(isNull(), eq("NIGHT_AGENT_DEFERRED"),
                eq("TRANSACTION"), eq(7L), eq("PENDING"), eq("LEFT_PENDING"), isNull());
    }

    @Test
    void alerteAbsorbee_chaineIgnoree() {
        when(repository.save(any(NightAgentDecision.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Outcome outcome = NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90);

        worker.apply(tx(), eval(), alert(AlertStatus.ABSORBED),
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"), outcome);

        verify(transactionClient).completeNightIntent(eq(7L), any());
        verify(alertService, never()).changeStatus(any(), any(), any(), any());
    }

    @Test
    void alerteDejaTerminee_chaineIgnoree() {
        when(repository.save(any(NightAgentDecision.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Outcome outcome = NightAgentDecisionRule.decide("LOW", "NORMAL", 0.90);

        worker.apply(tx(), eval(), alert(AlertStatus.RESOLVED),
                new AiAnalysisView(9L, "NORMAL", 0.9, "ok"), outcome);

        verify(alertService, never()).changeStatus(any(), any(), any(), any());
    }

    // --------------------------------------------------------- IA ABSENTE

    @Test
    void iaAbsente_ligneSansVerdict_laissee() {
        when(repository.save(any(NightAgentDecision.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NightAgentDecision row = worker.apply(tx(), eval(), alert(AlertStatus.OPEN),
                null, NightAgentDecisionRule.decide("LOW", null, null));

        assertThat(row.getAgentAction()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(row.getAiVerdict()).isNull();
        assertThat(row.getAiConfidence()).isNull();
        assertThat(row.getAgentReason()).contains("indisponible");
        verify(transactionClient, never()).completeNightIntent(org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
