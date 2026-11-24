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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.web.dto.AlertCreateRequest;
import tn.finrisk.alertcaseservice.web.error.AlertNotFoundException;
import tn.finrisk.alertcaseservice.web.error.ConflictException;

/**
 * Unite d'AlertService : RiskClient, repository et AuditService sont MOCKES,
 * aucune base ni aucun reseau. On teste les REGLES : une alerte par
 * evaluation, le graphe de transitions, resolvedAt en fin de cycle, trace.
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    private static final long ASSESSMENT = 42L;
    private static final long ALERT_ID = 9L;

    private static final EvaluationView EVAL = new EvaluationView(
            ASSESSMENT, 7L, new BigDecimal("72.00"), "HIGH", "PENDING_REVIEW",
            OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1");

    @Mock
    private AlertRepository alertRepository;
    @Mock
    private RiskClient riskClient;
    @Mock
    private AuditService auditService;
    /** Agregation : mockee ici, testee dans AlertGroupServiceTest. */
    @Mock
    private AlertGroupService groupService;

    @InjectMocks
    private AlertService service;

    // --------------------------------------------------------------- OUTILS

    private void stubSave() {
        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> {
            Alert alert = invocation.getArgument(0);
            if (alert.getAlertId() == null) {
                alert.setAlertId(ALERT_ID);
            }
            return alert;
        });
    }

    private Alert alert(AlertStatus status) {
        Alert alert = new Alert();
        alert.setAlertId(ALERT_ID);
        alert.setAssessmentId(ASSESSMENT);
        alert.setAlertCode("ALT-" + ASSESSMENT);
        alert.setAlertType(AlertType.HIGH_AMOUNT);
        alert.setSeverity(Severity.HIGH);
        alert.setStatus(status);
        return alert;
    }

    // ------------------------------------------------------------- CREATION

    @Test
    void creation_depuisEvaluation_codeDeriveEtTrace() {
        when(riskClient.getEvaluationById(ASSESSMENT)).thenReturn(EVAL);
        when(alertRepository.existsByAssessmentId(ASSESSMENT)).thenReturn(false);
        stubSave();

        Alert cree = service.create(
                new AlertCreateRequest(ASSESSMENT, AlertType.HIGH_AMOUNT, null, null),
                100L, "10.0.0.1");

        assertThat(cree.getAlertCode()).isEqualTo("ALT-42");
        assertThat(cree.getStatus()).isEqualTo(AlertStatus.OPEN);
        // severite ABSENTE dans la requete -> derivee du verdict du moteur (HIGH)
        assertThat(cree.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(cree.getResolvedAt()).isNull();
        verify(auditService).record(eq(100L), eq("ALERT_CREATED"), eq("ALERT"),
                eq(ALERT_ID), isNull(), eq("OPEN"), eq("10.0.0.1"));
        // L'agregation est tentee sur chaque creation (elle-meme defensive).
        verify(groupService).attach(eq(cree), eq(EVAL), eq(100L), eq("10.0.0.1"));
    }

    @Test
    void creation_severiteExpliciteRemplaceCelleDuMoteur() {
        when(riskClient.getEvaluationById(ASSESSMENT)).thenReturn(EVAL);
        when(alertRepository.existsByAssessmentId(ASSESSMENT)).thenReturn(false);
        stubSave();

        Alert cree = service.create(
                new AlertCreateRequest(ASSESSMENT, AlertType.GEOGRAPHIC, Severity.LOW, null),
                null, null);

        assertThat(cree.getSeverity()).isEqualTo(Severity.LOW);
    }

    @Test
    void creation_evaluationInconnue_404_sansEcriture() {
        when(riskClient.getEvaluationById(999L))
                .thenThrow(new RemoteResourceNotFoundException("evaluation", 999L));

        assertThatThrownBy(() -> service.create(
                new AlertCreateRequest(999L, AlertType.OTHER, null, null), null, null))
                .isInstanceOf(RemoteResourceNotFoundException.class)
                .hasMessage("evaluation introuvable chez le service distant : id = 999");

        verify(alertRepository, never()).save(any(Alert.class));
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void creation_riskServiceHorsLigne_503() {
        when(riskClient.getEvaluationById(ASSESSMENT))
                .thenThrow(new DownstreamUnavailableException("risk-service", null));

        assertThatThrownBy(() -> service.create(
                new AlertCreateRequest(ASSESSMENT, AlertType.OTHER, null, null), null, null))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasMessage("Service distant indisponible : risk-service");
    }

    @Test
    void creation_dejaAlertee_409() {
        when(riskClient.getEvaluationById(ASSESSMENT)).thenReturn(EVAL);
        when(alertRepository.existsByAssessmentId(ASSESSMENT)).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new AlertCreateRequest(ASSESSMENT, AlertType.HIGH_AMOUNT, null, null), null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Une alerte existe deja")
                .hasMessageContaining("ALT-42");

        verify(alertRepository, never()).save(any(Alert.class));
    }

    // ---------------------------------------------------------- TRANSITIONS

    @Test
    void transitionLegale_etatMisAJourEtTrace() {
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert(AlertStatus.OPEN)));
        stubSave();

        Alert maj = service.changeStatus(ALERT_ID, AlertStatus.ACKNOWLEDGED, 100L, null);

        assertThat(maj.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(maj.getResolvedAt()).isNull();
        verify(auditService).record(eq(100L), eq("ALERT_STATUS_CHANGED"), eq("ALERT"),
                eq(ALERT_ID), eq("OPEN"), eq("ACKNOWLEDGED"), isNull());
    }

    @Test
    void etatTerminal_renseigneResolvedAt() {
        when(alertRepository.findById(ALERT_ID))
                .thenReturn(Optional.of(alert(AlertStatus.IN_PROGRESS)));
        stubSave();

        Alert maj = service.changeStatus(ALERT_ID, AlertStatus.RESOLVED, null, null);

        assertThat(maj.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(maj.getResolvedAt()).isNotNull();
    }

    @Test
    void transitionIlllegale_409_sansEcriture() {
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert(AlertStatus.OPEN)));

        assertThatThrownBy(() -> service.changeStatus(ALERT_ID, AlertStatus.RESOLVED, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Transition d'alerte illegale : OPEN -> RESOLVED");

        verify(alertRepository, never()).save(any(Alert.class));
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void alerteInconnue_404() {
        when(alertRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(999L, AlertStatus.ACKNOWLEDGED, null, null))
                .isInstanceOf(AlertNotFoundException.class)
                .hasMessage("Alerte introuvable : alertId = 999");
    }
}
