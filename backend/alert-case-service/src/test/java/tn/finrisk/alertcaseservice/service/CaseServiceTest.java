package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.CaseComment;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.AlertType;
import tn.finrisk.alertcaseservice.domain.enums.CasePriority;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;
import tn.finrisk.alertcaseservice.domain.enums.CommentType;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.repository.CaseCommentRepository;
import tn.finrisk.alertcaseservice.repository.CaseRepository;
import tn.finrisk.alertcaseservice.web.dto.CaseOpenRequest;
import tn.finrisk.alertcaseservice.web.dto.CaseStatusRequest;
import tn.finrisk.alertcaseservice.web.error.AlertNotFoundException;
import tn.finrisk.alertcaseservice.web.error.BadRequestException;
import tn.finrisk.alertcaseservice.web.error.CaseNotFoundException;
import tn.finrisk.alertcaseservice.web.error.ConflictException;

/**
 * Unite de CaseService : tout est MOCKE (aucune base, aucun reseau).
 * On teste les REGLES : une case par alerte, pas d'enquete sur alerte
 * terminee, graphe de transitions, cloture (closedAt), commentaire
 * obligatoirement attribue (X-User-Id), case closee = fin des mutations.
 */
@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    private static final long ALERT_ID = 7L;
    private static final long CASE_ID = 8L;
    private static final long USER = 100L;

    @Mock
    private AlertRepository alertRepository;
    @Mock
    private CaseRepository caseRepository;
    @Mock
    private CaseCommentRepository commentRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private CaseService service;

    // --------------------------------------------------------------- OUTILS

    private Alert alert(AlertStatus status) {
        Alert alert = new Alert();
        alert.setAlertId(ALERT_ID);
        alert.setAssessmentId(42L);
        alert.setAlertCode("ALT-42");
        alert.setAlertType(AlertType.HIGH_AMOUNT);
        alert.setSeverity(Severity.HIGH);
        alert.setStatus(status);
        return alert;
    }

    private Case entity(CaseStatus status) {
        Case entity = new Case();
        entity.setCaseId(CASE_ID);
        entity.setAlertId(ALERT_ID);
        entity.setStatus(status);
        return entity;
    }

    private void stubCaseSave() {
        when(caseRepository.save(any(Case.class))).thenAnswer(invocation -> {
            Case entity = invocation.getArgument(0);
            if (entity.getCaseId() == null) {
                entity.setCaseId(CASE_ID);
            }
            return entity;
        });
    }

    private void stubCommentSave() {
        when(commentRepository.save(any(CaseComment.class))).thenAnswer(invocation -> {
            CaseComment comment = invocation.getArgument(0);
            if (comment.getCommentId() == null) {
                comment.setCommentId(3L);
            }
            return comment;
        });
    }

    // ------------------------------------------------------------ OUVERTURE

    @Test
    void ouverture_caseCreeeEtTracee() {
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert(AlertStatus.IN_PROGRESS)));
        when(caseRepository.existsByAlertId(ALERT_ID)).thenReturn(false);
        stubCaseSave();

        Case entity = service.open(ALERT_ID,
                new CaseOpenRequest(CasePriority.CRITICAL, 55L), USER, "10.0.0.1");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(entity.getPriority()).isEqualTo(CasePriority.CRITICAL);
        assertThat(entity.getAssignedTo()).isEqualTo(55L);
        // openedAt est renseigne par @PrePersist au moment du vrai INSERT :
        // inverifiable ici (save est un mock), couvert par l'integration
        verify(auditService).record(eq(USER), eq("CASE_OPENED"), eq("CASE"), eq(CASE_ID),
                isNull(), eq("OPEN"), eq("10.0.0.1"));
    }

    @Test
    void ouverture_assigneeParDefautAuDemandeur_prioriteMEDIUM() {
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert(AlertStatus.OPEN)));
        when(caseRepository.existsByAlertId(ALERT_ID)).thenReturn(false);
        stubCaseSave();

        Case entity = service.open(ALERT_ID, null, USER, null);

        assertThat(entity.getAssignedTo()).isEqualTo(USER);
        assertThat(entity.getPriority()).isEqualTo(CasePriority.MEDIUM);
    }

    @Test
    void ouverture_alerteInconnue_404() {
        when(alertRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(999L, null, null, null))
                .isInstanceOf(AlertNotFoundException.class)
                .hasMessage("Alerte introuvable : alertId = 999");
        verify(caseRepository, never()).save(any(Case.class));
    }

    @Test
    void ouverture_alerteTerminee_409() {
        when(alertRepository.findById(ALERT_ID))
                .thenReturn(Optional.of(alert(AlertStatus.FALSE_POSITIVE)));

        assertThatThrownBy(() -> service.open(ALERT_ID, null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("etat terminal")
                .hasMessageContaining("FALSE_POSITIVE");
    }

    @Test
    void ouverture_dejaOuverte_409() {
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert(AlertStatus.OPEN)));
        when(caseRepository.existsByAlertId(ALERT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.open(ALERT_ID, null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Une case existe deja pour cette alerte : alertId = 7");
        verify(caseRepository, never()).save(any(Case.class));
    }

    // ---------------------------------------------------------- TRANSITIONS

    @Test
    void transitionAvecResultatPuisCloture_traceEtClosedAt() {
        Case ouverte = entity(CaseStatus.UNDER_REVIEW);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(ouverte));
        stubCaseSave();

        Case confirmee = service.changeStatus(CASE_ID,
                new CaseStatusRequest(CaseStatus.CONFIRMED, "CONFIRMED_FRAUD"), USER, null);
        assertThat(confirmee.getStatus()).isEqualTo(CaseStatus.CONFIRMED);
        assertThat(confirmee.getInvestigationResult()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(confirmee.getClosedAt()).isNull(); // CONFIRMED n'est pas terminal

        Case closee = service.changeStatus(CASE_ID,
                new CaseStatusRequest(CaseStatus.CLOSED, null), USER, null);
        assertThat(closee.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(closee.getClosedAt()).isNotNull(); // la cloture est tracee

        verify(auditService).record(eq(USER), eq("CASE_STATUS_CHANGED"), eq("CASE"),
                eq(CASE_ID), eq("UNDER_REVIEW"), eq("CONFIRMED"), isNull());
        verify(auditService).record(eq(USER), eq("CASE_STATUS_CHANGED"), eq("CASE"),
                eq(CASE_ID), eq("CONFIRMED"), eq("CLOSED"), isNull());
    }

    @Test
    void transitionIlllegale_409() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(entity(CaseStatus.OPEN)));

        assertThatThrownBy(() -> service.changeStatus(CASE_ID,
                new CaseStatusRequest(CaseStatus.CONFIRMED, null), null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Transition de case illegale : OPEN -> CONFIRMED");
        verify(caseRepository, never()).save(any(Case.class));
    }

    @Test
    void caseCloturee_plusAucuneMutation_409() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(entity(CaseStatus.CLOSED)));

        assertThatThrownBy(() -> service.changeStatus(CASE_ID,
                new CaseStatusRequest(CaseStatus.UNDER_REVIEW, null), null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Case cloturee : aucune mutation");
    }

    @Test
    void caseInconnue_404() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(999L,
                new CaseStatusRequest(CaseStatus.UNDER_REVIEW, null), null, null))
                .isInstanceOf(CaseNotFoundException.class)
                .hasMessage("Case introuvable : caseId = 999");
    }

    // --------------------------------------------------------- COMMENTAIRES

    @Test
    void commentaire_attribueEtTrace() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(entity(CaseStatus.UNDER_REVIEW)));
        stubCommentSave();

        CaseComment comment = service.addComment(CASE_ID, "Preuve : virement DE",
                CommentType.EVIDENCE, USER, null);

        assertThat(comment.getUserId()).isEqualTo(USER);
        assertThat(comment.getCommentType()).isEqualTo(CommentType.EVIDENCE);
        // trace rattachee a la CASE pour figer le fil dans /api/audit
        verify(auditService).record(eq(USER), eq("COMMENT_ADDED"), eq("CASE"),
                eq(CASE_ID), isNull(), eq("EVIDENCE"), isNull());
    }

    @Test
    void commentaire_sansAuteur_400() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(entity(CaseStatus.OPEN)));

        assertThatThrownBy(() -> service.addComment(CASE_ID, "anon", null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("X-User-Id");
        verify(commentRepository, never()).save(any(CaseComment.class));
    }

    @Test
    void commentaire_caseCloturee_409() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(entity(CaseStatus.CLOSED)));

        assertThatThrownBy(() -> service.addComment(CASE_ID, "trop tard", null, USER, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Case cloturee : plus aucun commentaire");
    }

    @Test
    void commentaire_caseInconnue_404() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addComment(999L, "x", null, USER, null))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void fil_commentaires_caseInconnue_404() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.comments(999L))
                .isInstanceOf(CaseNotFoundException.class);
        verify(commentRepository, never()).findByCaseIdOrderByCreatedAtAsc(any());
    }
}
