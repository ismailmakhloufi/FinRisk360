package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tn.finrisk.alertcaseservice.client.AiClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.PageView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.repository.NightAgentDecisionRepository;
import tn.finrisk.alertcaseservice.service.NightAgentDecisionRule.Outcome;

/**
 * Unite de NightAgentService : clients et worker MOCKES. On teste le
 * BALAYAGE (compteurs, resilience) et la FENETRE horaire — la matrice
 * elle-meme est couverte par NightAgentDecisionRuleTest.
 */
@ExtendWith(MockitoExtension.class)
class NightAgentServiceTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");

    @Mock
    private TransactionClient transactionClient;
    @Mock
    private RiskClient riskClient;
    @Mock
    private AiClient aiClient;
    @Mock
    private AlertRepository alertRepository;
    @Mock
    private NightAgentWorker worker;
    @Mock
    private NightAgentDecisionRepository repository;

    @Mock
    private NightAgentIntentStore intents;

    private NightAgentService service;

    @Test
    void fenetreSeTermineA8Heures() {
        NightAgentService agent = new NightAgentService(transactionClient, riskClient, aiClient,
                alertRepository, worker, repository, intents, true, 17, 8, 50);
        assertThat(agent.inWindow(LocalTime.of(16, 59))).isFalse();
        assertThat(agent.inWindow(LocalTime.of(17, 0))).isTrue();
        assertThat(agent.inWindow(LocalTime.of(7, 59))).isTrue();
        assertThat(agent.inWindow(LocalTime.of(8, 0))).isFalse();
    }

    @Test
    void declenchementManuel_horsFenetreNeContacteAucunService() {
        service = new NightAgentService(transactionClient, riskClient, aiClient,
                alertRepository, worker, repository, intents, true, 17, 8, 50);
        service.setClockForTest(Clock.fixed(Instant.parse("2026-01-15T08:00:00Z"), ZoneOffset.UTC));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runManual())
                .isInstanceOf(tn.finrisk.alertcaseservice.web.error.ConflictException.class);
        org.mockito.Mockito.verifyNoInteractions(transactionClient);
    }

    @Test
    void declenchementManuel_a17hEstAutorise() {
        service.setClockForTest(Clock.fixed(Instant.parse("2026-01-15T17:00:00Z"), ZoneOffset.UTC));
        fileAttente();
        assertThat(service.runManual().scanned()).isZero();
    }

    @Test
    void validationSuspendue_memeUnAvisFavorableResteEnAttente() {
        ReflectionTestUtils.setField(service, "autoValidationEnabled", false);
        fileAttente(tx(1));
        when(riskClient.getEvaluationByTransaction(1L)).thenReturn(eval(1L, "LOW"));
        when(alertRepository.findByAssessmentId(43L))
                .thenReturn(Optional.of(alert(9L, 43L, AlertStatus.OPEN)));
        when(aiClient.analyze(9L)).thenReturn(new AiAnalysisView(9L, "NORMAL", 0.9, "ok"));
        var summary = service.run();
        assertThat(summary.validated()).isZero();
        assertThat(summary.deferred()).isEqualTo(1);
        ArgumentCaptor<Outcome> outcome = ArgumentCaptor.forClass(Outcome.class);
        verify(worker).apply(any(), any(), any(), any(), outcome.capture());
        assertThat(outcome.getValue().action()).isEqualTo(AgentAction.LEFT_PENDING);
        assertThat(outcome.getValue().reason()).contains("suspendue");
    }

    @BeforeEach
    void setUp() {
        // Constructeur a primitifs : instanciation manuelle (comme AlertGroupService).
        service = new NightAgentService(transactionClient, riskClient, aiClient,
                alertRepository, worker, repository, intents, true, 17, 9, 50);
        ReflectionTestUtils.setField(service, "autoValidationEnabled", true);
        service.setClockForTest(Clock.fixed(Instant.parse("2026-01-15T18:00:00Z"), ZoneOffset.UTC));
    }

    // --------------------------------------------------------------- OUTILS

    private TransactionView tx(long id) {
        return new TransactionView(id, 1L, new BigDecimal("500.00"), "TND",
                "TRANSFER", T, "bené", null, "PENDING");
    }

    private EvaluationView eval(long txId, String level) {
        return new EvaluationView(42L + txId, txId, new BigDecimal("20.00"),
                level, "MONITORING", T, "v1");
    }

    private Alert alert(long alertId, long assessmentId, AlertStatus status) {
        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setAssessmentId(assessmentId);
        alert.setAlertCode("ALT-" + assessmentId);
        alert.setAlertType(AlertType.OTHER);
        alert.setSeverity(Severity.LOW);
        alert.setStatus(status);
        alert.setCreatedAt(T);
        return alert;
    }

    private void fileAttente(TransactionView... txs) {
        when(transactionClient.listByStatus(eq("PENDING"), anyInt(), anyInt()))
                .thenReturn(new PageView<>(List.of(txs), txs.length));
    }

    // ------------------------------------------------------------ BALAYAGE

    @Test
    void run_compteValideesLaisseesEtSauttees() {
        fileAttente(tx(1), tx(2), tx(3));

        // tx 1 (assessment 43) : LOW + IA NORMAL -> validee
        when(riskClient.getEvaluationByTransaction(1L)).thenReturn(eval(1L, "LOW"));
        when(alertRepository.findByAssessmentId(43L))
                .thenReturn(Optional.of(alert(9L, 43L, AlertStatus.OPEN)));
        when(aiClient.analyze(9L)).thenReturn(new AiAnalysisView(9L, "NORMAL", 0.9, "ok"));

        // tx 2 (assessment 44) : MEDIUM + IA INCERTAIN -> laissee
        when(riskClient.getEvaluationByTransaction(2L)).thenReturn(eval(2L, "MEDIUM"));
        when(alertRepository.findByAssessmentId(44L))
                .thenReturn(Optional.of(alert(10L, 44L, AlertStatus.OPEN)));
        when(aiClient.analyze(10L)).thenReturn(new AiAnalysisView(10L, "INCERTAIN", 0.5, "bof"));

        // tx 3 : jamais evaluee -> sautee
        when(riskClient.getEvaluationByTransaction(3L)).thenReturn(null);

        NightAgentService.RunSummary summary = service.run();

        assertThat(summary.scanned()).isEqualTo(3);
        assertThat(summary.validated()).isEqualTo(1);
        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);

        ArgumentCaptor<Outcome> captor = ArgumentCaptor.forClass(Outcome.class);
        verify(worker, org.mockito.Mockito.times(2)).apply(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getAllValues()).extracting(Outcome::action)
                .containsExactly(AgentAction.VALIDATED, AgentAction.LEFT_PENDING);
    }

    @Test
    void run_iaInjoignable_laisseSansSecondAvis() {
        fileAttente(tx(1));
        when(riskClient.getEvaluationByTransaction(1L)).thenReturn(eval(1L, "LOW"));
        when(alertRepository.findByAssessmentId(43L))
                .thenReturn(Optional.of(alert(9L, 43L, AlertStatus.OPEN)));
        when(aiClient.analyze(9L))
                .thenThrow(new DownstreamUnavailableException("ai-assistant-service",
                        new IllegalStateException("panne")));

        NightAgentService.RunSummary summary = service.run();

        assertThat(summary.deferred()).isEqualTo(1);
        ArgumentCaptor<AiAnalysisView> aiCaptor = ArgumentCaptor.forClass(AiAnalysisView.class);
        verify(worker).apply(any(), any(), any(), aiCaptor.capture(), any());
        assertThat(aiCaptor.getValue()).isNull(); // pas d'avis -> matrice "IA indisponible"
    }

    @Test
    void run_erreurSurUneTx_neTuePasLeBalayage() {
        fileAttente(tx(1), tx(2));
        when(riskClient.getEvaluationByTransaction(1L))
                .thenThrow(new DownstreamUnavailableException("risk-service",
                        new IllegalStateException("panne")));
        when(riskClient.getEvaluationByTransaction(2L)).thenReturn(eval(2L, "LOW"));
        when(alertRepository.findByAssessmentId(44L))
                .thenReturn(Optional.of(alert(10L, 44L, AlertStatus.OPEN)));
        when(aiClient.analyze(10L)).thenReturn(new AiAnalysisView(10L, "NORMAL", 0.9, "ok"));

        NightAgentService.RunSummary summary = service.run();

        assertThat(summary.scanned()).isEqualTo(2);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.validated()).isEqualTo(1);
    }

    @Test
    void run_sansAlerte_sautée_sansLigne() {
        fileAttente(tx(1));
        when(riskClient.getEvaluationByTransaction(1L)).thenReturn(eval(1L, "LOW"));
        when(alertRepository.findByAssessmentId(43L)).thenReturn(Optional.empty());

        NightAgentService.RunSummary summary = service.run();

        assertThat(summary.skipped()).isEqualTo(1);
        verify(worker, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void run_neReemettePasUneTxDejaRapportee() {
        // Chaque tick revoit la meme tx en attente : sans ce garde-fou, le
        // rapport se remplit de lignes « LAISSE » identiques (x5 en demo).
        fileAttente(tx(1));
        when(repository.existsByTransactionId(7L)).thenReturn(true);

        NightAgentService.RunSummary summary = service.run();

        assertThat(summary.scanned()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.deferred()).isZero();
        assertThat(summary.validated()).isZero();
        verify(riskClient, never()).getEvaluationByTransaction(anyLong());
        verify(worker, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void run_budgetLimiteLesTxJugeesSeules() {
        // Budget de 2 : les 2 premieres tx jugables passent, la 3e n'est
        // meme pas examinee (sans alerte, le skip ne consomme pas le budget).
        fileAttente(tx(1), tx(2), tx(3));
        when(riskClient.getEvaluationByTransaction(1L)).thenReturn(eval(1L, "LOW"));
        when(alertRepository.findByAssessmentId(43L))
                .thenReturn(Optional.of(alert(9L, 43L, AlertStatus.OPEN)));
        when(aiClient.analyze(9L)).thenReturn(new AiAnalysisView(9L, "NORMAL", 0.9, "ok"));
        when(riskClient.getEvaluationByTransaction(2L)).thenReturn(eval(2L, "LOW"));
        when(alertRepository.findByAssessmentId(44L))
                .thenReturn(Optional.of(alert(10L, 44L, AlertStatus.OPEN)));
        when(aiClient.analyze(10L)).thenReturn(new AiAnalysisView(10L, "NORMAL", 0.9, "ok"));

        NightAgentService petitBudget = new NightAgentService(transactionClient, riskClient,
                aiClient, alertRepository, worker, repository, intents, true, 17, 9, 2);
        ReflectionTestUtils.setField(petitBudget, "autoValidationEnabled", true);
        petitBudget.setClockForTest(Clock.fixed(Instant.parse("2026-01-15T18:00:00Z"), ZoneOffset.UTC));
        NightAgentService.RunSummary summary = petitBudget.run();

        assertThat(summary.scanned()).isEqualTo(2);
        assertThat(summary.validated()).isEqualTo(2);
        assertThat(summary.skipped()).isZero();
        verify(riskClient, never()).getEvaluationByTransaction(3L);
    }

    @Test
    void run_passeLesPages_pourAtteindreLaTxAlertee() {
        // Page 0 : 100 txs sans evaluation — toutes sautees, SANS budget use.
        java.util.List<TransactionView> page0 = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            page0.add(tx(1000L + i));
        }
        when(transactionClient.listByStatus("PENDING", 0, 100))
                .thenReturn(new PageView<>(page0, 101));
        // Page 1 : la seule tx alertee — elle est JUGEE malgre la distance.
        when(transactionClient.listByStatus("PENDING", 1, 100))
                .thenReturn(new PageView<>(List.of(tx(1)), 101));
        when(riskClient.getEvaluationByTransaction(1L)).thenReturn(eval(1L, "LOW"));
        when(alertRepository.findByAssessmentId(43L))
                .thenReturn(Optional.of(alert(9L, 43L, AlertStatus.OPEN)));
        when(aiClient.analyze(9L)).thenReturn(new AiAnalysisView(9L, "NORMAL", 0.9, "ok"));

        NightAgentService.RunSummary summary = service.run();

        assertThat(summary.scanned()).isEqualTo(101);
        assertThat(summary.validated()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(100);
        verify(transactionClient).listByStatus("PENDING", 1, 100);
    }

    // ------------------------------------------------------------- FENETRE

    @Test
    void fenetre_17h_a_9h() {
        assertThat(service.inWindow(LocalTime.of(17, 0))).isTrue();
        assertThat(service.inWindow(LocalTime.of(23, 59))).isTrue();
        assertThat(service.inWindow(LocalTime.of(0, 0))).isTrue();
        assertThat(service.inWindow(LocalTime.of(8, 59))).isTrue();
        assertThat(service.inWindow(LocalTime.of(9, 0))).isFalse();
        assertThat(service.inWindow(LocalTime.of(12, 0))).isFalse();
        assertThat(service.inWindow(LocalTime.of(16, 59))).isFalse();
    }

    @Test
    void fenetreVide_estInactive() {
        NightAgentService toujoursFerme = new NightAgentService(transactionClient, riskClient,
                aiClient, alertRepository, worker, repository, intents, true, 5, 5, 50);
        assertThat(toujoursFerme.inWindow(LocalTime.of(5, 0))).isFalse();
    }
}
