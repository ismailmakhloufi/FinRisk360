package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.finrisk.alertcaseservice.client.AccountClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AccountView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.AlertGroup;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.GroupScope;
import tn.finrisk.alertcaseservice.domain.enums.GroupStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertGroupRepository;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.web.error.BadRequestException;
import tn.finrisk.alertcaseservice.web.error.ConflictException;

/**
 * Unite d'AlertGroupService : repositories et clients distants MOCKES, aucune
 * base ni reseau. On teste les REGLES d'agregation : perimetre compte
 * emetteur, plafonds de compteur, resilient a une panne.
 *
 * Les natives queries (closeExpiredOpenGroup / insertOpenGroupIfAbsent /
 * incrementAndEscalate) sont MOCKEES ici : leur SQL est valide par
 * AlertCaseIntegrationTest (base reelle) et par l'E2E de rafale.
 */
@ExtendWith(MockitoExtension.class)
class AlertGroupServiceTest {

    private static final long ACCOUNT_ID = 101L;
    private static final long GROUP_ID = 50L;
    private static final long TX_ID = 7L;
    private static final BigDecimal MONTANT = new BigDecimal("100.00");

    private static final EvaluationView EVAL = new EvaluationView(
            42L, TX_ID, new BigDecimal("5.00"), "LOW", "PENDING_REVIEW",
            OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1");

    @Mock
    private AlertGroupRepository groupRepository;
    @Mock
    private AlertRepository alertRepository;
    @Mock
    private TransactionClient transactionClient;
    @Mock
    private AccountClient accountClient;
    @Mock
    private RiskClient riskClient;
    @Mock
    private AuditService auditService;

    /**
     * Instancie a la main : le constructeur porte des PRIMITIFS (les seuils
     * @Value) qu'@InjectMocks ne sait pas injecter.
     */
    private AlertGroupService service;

    @BeforeEach
    void setUp() {
        service = new AlertGroupService(groupRepository, alertRepository,
                transactionClient, accountClient, riskClient, auditService, 24, 5, 10);
    }

    // --------------------------------------------------------------- OUTILS

    private TransactionView tx() {
        return new TransactionView(TX_ID, ACCOUNT_ID, MONTANT, "TND",
                "TRANSFER", OffsetDateTime.now(ZoneOffset.UTC), "Dupont", null, "COMPLETED");
    }

    private Alert alert(Severity severity, OffsetDateTime createdAt) {
        Alert alert = new Alert();
        alert.setAlertId(1L);
        alert.setAssessmentId(42L);
        alert.setAlertCode("ALT-42");
        alert.setAlertType(AlertType.HIGH_AMOUNT);
        alert.setSeverity(severity);
        alert.setStatus(AlertStatus.OPEN);
        alert.setCreatedAt(createdAt);
        return alert;
    }

    /** Projection non geree du groupe ouvert (findOpenGroupRow). */
    private AlertGroupRepository.OpenGroupRow openRow(String severity, Long customerId) {
        return new AlertGroupRepository.OpenGroupRow() {
            @Override
            public Long getGroupId() {
                return GROUP_ID;
            }

            @Override
            public String getSeverity() {
                return severity;
            }

            @Override
            public Long getCustomerId() {
                return customerId;
            }
        };
    }

    /** Stubs communs : la fenetre n'est pas expiree, le groupe existe deja. */
    private void stubExistingGroup(Severity severity, Long customerId) {
        when(groupRepository.findOpenGroupRow(
                GroupScope.ACCOUNT.name(), String.valueOf(ACCOUNT_ID)))
                .thenReturn(Optional.of(openRow(severity.name(), customerId)));
    }

    /** Reponse de incrementAndEscalate (projection native). */
    private AlertGroupRepository.GroupAggregateRow row(int count, String severity) {
        return new AlertGroupRepository.GroupAggregateRow() {
            @Override
            public int getRelatedCount() {
                return count;
            }

            @Override
            public BigDecimal getTotalAmount() {
                return MONTANT.multiply(BigDecimal.valueOf(count));
            }

            @Override
            public String getSeverity() {
                return severity;
            }
        };
    }

    // ------------------------------------------------------------ PLAFONDS

    @Test
    void plafondCompteur_cinqAlertesAuMoinsMedium() {
        assertThat(service.withCountFloor(Severity.LOW, 5)).isEqualTo(Severity.MEDIUM);
        assertThat(service.withCountFloor(Severity.LOW, 9)).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void plafondCompteur_dixAlertesAuMoinsHigh() {
        assertThat(service.withCountFloor(Severity.LOW, 10)).isEqualTo(Severity.HIGH);
        assertThat(service.withCountFloor(Severity.CRITICAL, 10)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void plafondCompteur_sousSeuil_onGardeLaSeveriteIndividuelle() {
        assertThat(service.withCountFloor(Severity.LOW, 1)).isEqualTo(Severity.LOW);
        assertThat(service.withCountFloor(Severity.HIGH, 4)).isEqualTo(Severity.HIGH);
    }

    // --------------------------------------------------------- ATTACH (cas)

    @Test
    void attach_premiereAlerte_ouvreLeGroupeEtResoutLeClient() {
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(accountClient.getById(ACCOUNT_ID))
                .thenReturn(new AccountView(ACCOUNT_ID, 77L, "TN13-0001", "TND"));
        // La fenetre n'etant pas expiree, aucune fermeture.
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        // Aucun groupe ouvert -> l'insert le cree.
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(GROUP_ID);
        stubExistingGroup(Severity.LOW, null);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(1, "LOW"));

        Alert alert = alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC));
        AlertGroup group = service.attach(alert, EVAL, 100L, "10.0.0.1");

        assertThat(group).isNotNull();
        assertThat(group.getRelatedCount()).isEqualTo(1);
        assertThat(group.getSeverity()).isEqualTo(Severity.LOW);
        assertThat(group.getCustomerId()).isEqualTo(77L);
        assertThat(alert.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(alert.getAmount()).isEqualByComparingTo("100.00");
        // La creation du groupe est tracée dans audit_logs.
        verify(auditService).record(eq(100L),
                eq(AlertGroupService.AUDIT_GROUP_CREATED),
                eq("ALERT_GROUP"), eq(GROUP_ID), any(), anyString(), eq("10.0.0.1"));
    }

    @Test
    void attach_cinquiemeAlerte_basculeLeGroupeEnMedium() {
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(null); // deja cree par un concurrent
        stubExistingGroup(Severity.LOW, 77L);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(5, "MEDIUM"));

        AlertGroup group = service.attach(alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC)),
                EVAL, 1L, null);

        assertThat(group.getRelatedCount()).isEqualTo(5);
        assertThat(group.getSeverity()).isEqualTo(Severity.MEDIUM);
        // Escalade tracée : le changement de severite agrégée laisse une empreinte.
        verify(auditService).record(any(),
                eq(AlertGroupService.AUDIT_GROUP_ESCALATED),
                any(), any(), any(), any(), any());
    }

    @Test
    void attach_deuxiemeAlerte_absorbeeParLeGroupe() {
        // Modele « 10 transactions -> 1 alerte » : la 2e alerte du compte
        // est ABSORBEE (masquee de la file), la 1re reste OPEN.
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(null);
        stubExistingGroup(Severity.LOW, 77L);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(2, "LOW"));

        Alert alert = alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC));
        service.attach(alert, EVAL, 1L, null);

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ABSORBED);
        assertThat(alert.getGroupId()).isEqualTo(GROUP_ID);
    }

    @Test
    void attach_dixiemeAlerte_basculeLeGroupeEnHigh() {
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(null);
        stubExistingGroup(Severity.MEDIUM, 77L);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(10, "HIGH"));

        AlertGroup group = service.attach(alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC)),
                EVAL, 1L, null);

        assertThat(group.getRelatedCount()).isEqualTo(10);
        assertThat(group.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void attach_fenetreExpiree_fermeLancienEtOuvreUnNouveau() {
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        // La fenetre etant passee, closeExpiredOpenGroup cloture l'ancien
        // groupe (libere le slot « ouvert » de l'index partiel).
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(1);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(GROUP_ID);
        stubExistingGroup(Severity.LOW, null);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(1, "LOW"));

        AlertGroup group = service.attach(alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC)),
                EVAL, 1L, null);

        assertThat(group.getRelatedCount()).isEqualTo(1);
        // Un nouvel episode repart a une severite fiable : le pattern precedent
        // (HIGH) ne doit PAS contaminer le nouvel episode.
        assertThat(group.getSeverity()).isEqualTo(Severity.LOW);
    }

    // --------------------------------------------------------- RESILIENCE

    @Test
    void attach_transactionServiceHorsLigne_alerteSansGroupe() {
        when(transactionClient.getById(TX_ID))
                .thenThrow(new DownstreamUnavailableException("transaction-service", null));

        Alert alert = alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC));
        AlertGroup group = service.attach(alert, EVAL, 1L, null);

        assertThat(group).isNull();
        assertThat(alert.getGroupId()).isNull();
        verify(groupRepository, never()).insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void attach_accountServiceHorsLigne_groupeQuandMeme_customerIdNull() {
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(accountClient.getById(ACCOUNT_ID))
                .thenThrow(new DownstreamUnavailableException("account-service", null));
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(GROUP_ID);
        stubExistingGroup(Severity.LOW, null);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(1, "LOW"));

        AlertGroup group = service.attach(alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC)),
                EVAL, 1L, null);

        assertThat(group).isNotNull();
        assertThat(group.getCustomerId()).isNull();
    }

    @Test
    void attach_concurrentDejaCree_neFaitPasEchouerLAlerte() {
        // Un concurrent a gagné la course : insertOpenGroupIfAbsent retourne
        // null (ON CONFLICT DO NOTHING). L'alerte doit quand meme etre rattachee
        // au groupe du concurrent — sans abort de transaction.
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(null);
        stubExistingGroup(Severity.LOW, 77L);
        when(groupRepository.incrementAndEscalate(
                eq(GROUP_ID), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(row(3, "LOW"));

        Alert alert = alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC));
        AlertGroup group = service.attach(alert, EVAL, 1L, null);

        assertThat(group).isNotNull();
        assertThat(group.getRelatedCount()).isEqualTo(3);
        assertThat(alert.getGroupId()).isEqualTo(GROUP_ID);
    }

    @Test
    void attach_groupeIntrouvableApresInsert_alerteSansGroupe() {
        // Defensif : l'insert a reussi mais la relecture ne trouve rien
        // (index partiel casse ?) -> on laisse l'alerte sans agregation
        // plutot que de faire echouer sa creation.
        when(transactionClient.getById(TX_ID)).thenReturn(tx());
        when(groupRepository.closeExpiredOpenGroup(anyString(), anyString(), any()))
                .thenReturn(0);
        when(groupRepository.insertOpenGroupIfAbsent(
                anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(GROUP_ID);
        when(groupRepository.findOpenGroupRow(
                GroupScope.ACCOUNT.name(), String.valueOf(ACCOUNT_ID)))
                .thenReturn(Optional.empty());

        Alert alert = alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC));
        AlertGroup group = service.attach(alert, EVAL, 1L, null);

        assertThat(group).isNull();
        assertThat(alert.getGroupId()).isNull();
    }

    // ----------------------------------------------------------- DECISION

    /** Groupe ouvert minimal pour decide(). */
    private AlertGroup openGroup() {
        AlertGroup group = new AlertGroup();
        group.setGroupId(GROUP_ID);
        group.setScopeType(GroupScope.ACCOUNT);
        group.setScopeValue(String.valueOf(ACCOUNT_ID));
        group.setWindowStart(OffsetDateTime.now(ZoneOffset.UTC));
        group.setWindowHours(24);
        group.setStatus(GroupStatus.OPEN);
        group.setRelatedCount(2);
        group.setTotalAmount(new BigDecimal("200.00"));
        group.setSeverity(Severity.LOW);
        return group;
    }

    private Alert member(long alertId, long assessmentId, AlertStatus status) {
        Alert alert = alert(Severity.LOW, OffsetDateTime.now(ZoneOffset.UTC));
        alert.setAlertId(alertId);
        alert.setAssessmentId(assessmentId);
        alert.setAlertCode("ALT-" + assessmentId);
        alert.setStatus(status);
        alert.setGroupId(GROUP_ID);
        return alert;
    }

    @Test
    void decide_refuse_annuleChaqueTransactionEtClotureLeGroupe() {
        Alert open = member(1L, 42L, AlertStatus.OPEN);
        Alert absorbed = member(2L, 43L, AlertStatus.ABSORBED);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(openGroup()));
        when(alertRepository.findByGroupIdOrderByCreatedAtAsc(GROUP_ID))
                .thenReturn(List.of(open, absorbed));
        when(riskClient.getEvaluationById(42L)).thenReturn(EVAL);
        when(riskClient.getEvaluationById(43L)).thenReturn(new EvaluationView(
                43L, 8L, new BigDecimal("5.00"), "LOW", "PENDING_REVIEW",
                OffsetDateTime.parse("2026-01-15T12:00:00Z"), "v1"));
        when(groupRepository.closeGroupIfOpen(GROUP_ID, GroupStatus.REFUSED.name()))
                .thenReturn(1);

        AlertGroup view = service.decide(GROUP_ID, "REFUSE", "structuring confirme",
                100L, "10.0.0.1");

        assertThat(view.getStatus()).isEqualTo(GroupStatus.REFUSED);
        // Chaque transaction du groupe est annulee + remboursee.
        verify(transactionClient).cancelAndRefund(7L);
        verify(transactionClient).cancelAndRefund(8L);
        verify(transactionClient, times(2)).cancelAndRefund(anyLong());
        // Alertes membres cloturees (y compris l'absorbee) : RESOLVED.
        assertThat(open.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(open.getResolvedAt()).isNotNull();
        assertThat(absorbed.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(absorbed.getResolvedAt()).isNotNull();
        verify(auditService).record(eq(100L), eq(AlertGroupService.AUDIT_GROUP_REFUSED),
                eq("ALERT_GROUP"), eq(GROUP_ID), any(), anyString(), eq("10.0.0.1"));
    }

    @Test
    void decide_accept_groupeResoluAlertesFalsePositive_sansToucherLesTransactions() {
        Alert open = member(1L, 42L, AlertStatus.OPEN);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(openGroup()));
        when(alertRepository.findByGroupIdOrderByCreatedAtAsc(GROUP_ID))
                .thenReturn(List.of(open));
        when(groupRepository.closeGroupIfOpen(GROUP_ID, GroupStatus.RESOLVED.name()))
                .thenReturn(1);

        AlertGroup view = service.decide(GROUP_ID, "accept", null, 100L, null);

        assertThat(view.getStatus()).isEqualTo(GroupStatus.RESOLVED);
        assertThat(open.getStatus()).isEqualTo(AlertStatus.FALSE_POSITIVE);
        assertThat(open.getResolvedAt()).isNotNull();
        verify(transactionClient, never()).cancelAndRefund(anyLong());
        verify(auditService).record(eq(100L), eq(AlertGroupService.AUDIT_GROUP_ACCEPTED),
                eq("ALERT_GROUP"), eq(GROUP_ID), any(), anyString(), any());
    }

    @Test
    void decide_groupeDejaTranche_409() {
        AlertGroup closed = openGroup();
        closed.setStatus(GroupStatus.RESOLVED);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.decide(GROUP_ID, "REFUSE", null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("deja tranche");
    }

    @Test
    void decide_decisionInconnue_400() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(openGroup()));

        assertThatThrownBy(() -> service.decide(GROUP_ID, "BOF", null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("BOF");
    }

    @Test
    void decide_refuse_echecAnnulationDistante_transactionLocaleNonEclose() {
        // Le remboursement echoue (panne) : la decision est REJOUABLE —
        // ni le groupe ni les alertes ne sont modifies (rollback local).
        Alert open = member(1L, 42L, AlertStatus.OPEN);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(openGroup()));
        when(alertRepository.findByGroupIdOrderByCreatedAtAsc(GROUP_ID))
                .thenReturn(List.of(open));
        when(riskClient.getEvaluationById(42L)).thenReturn(EVAL);
        when(transactionClient.cancelAndRefund(7L))
                .thenThrow(new DownstreamUnavailableException("transaction-service", null));

        assertThatThrownBy(() -> service.decide(GROUP_ID, "REFUSE", null, null, null))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(groupRepository, never()).closeGroupIfOpen(anyLong(), anyString());
        assertThat(open.getStatus()).isEqualTo(AlertStatus.OPEN);
    }
}
