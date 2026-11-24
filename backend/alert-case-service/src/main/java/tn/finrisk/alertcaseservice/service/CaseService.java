package tn.finrisk.alertcaseservice.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.CaseComment;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;
import tn.finrisk.alertcaseservice.domain.enums.CommentType;
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
 * Vie d'une case d'enquete : ouverture depuis une alerte, transitions,
 * fil de commentaires, cloture tracee.
 *
 * Regles metier (toutes -> 409 sauf mention) :
 *  - l'alerte doit exister ; UNE SEULE case par alerte ;
 *  - pas d'enquete sur une alerte deja terminee (RESOLVED / FALSE_POSITIVE) ;
 *  - transitions selon le graphe CaseStatus.canMoveTo ;
 *  - passage terminal (CLOSED / FALSE_POSITIVE) -> closed_at renseigne ;
 *  - case cloturee = plus aucun commentaire (la preuve s'arrete a la cloture) ;
 *  - commentaire sans userId -> 400 (user_id NOT NULL, pas d'authentification
 *    tant que M5 n'existe pas : on n'invente pas d'auteur).
 *
 * Chaque mutation passe par AuditService : rien ne bouge sans trace.
 */
@Service
public class CaseService {

    static final String AUDIT_CASE_OPENED = "CASE_OPENED";
    static final String AUDIT_CASE_STATUS_CHANGED = "CASE_STATUS_CHANGED";
    static final String AUDIT_COMMENT_ADDED = "COMMENT_ADDED";

    private final AlertRepository alertRepository;
    private final CaseRepository caseRepository;
    private final CaseCommentRepository commentRepository;
    private final AuditService auditService;

    public CaseService(AlertRepository alertRepository, CaseRepository caseRepository,
                       CaseCommentRepository commentRepository, AuditService auditService) {
        this.alertRepository = alertRepository;
        this.caseRepository = caseRepository;
        this.commentRepository = commentRepository;
        this.auditService = auditService;
    }

    /** POST /api/alerts/{alertId}/cases : ouverture du dossier d'enquete. */
    @Transactional
    public Case open(Long alertId, CaseOpenRequest request, Long userId, String ipAddress) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new AlertNotFoundException("alertId", alertId));
        if (alert.getStatus().isTerminal()) {
            throw new ConflictException("Alerte en etat terminal : plus aucune ouverture de case ("
                    + alert.getStatus().name() + ")");
        }
        if (caseRepository.existsByAlertId(alertId)) {
            throw new ConflictException("Une case existe deja pour cette alerte : alertId = " + alertId);
        }
        Case entity = new Case();
        entity.setAlertId(alertId);
        entity.setAssignedTo(request != null && request.assignedTo() != null
                ? request.assignedTo() : userId);
        if (request != null && request.priority() != null) {
            entity.setPriority(request.priority());
        }
        Case saved = caseRepository.save(entity);

        auditService.record(userId, AUDIT_CASE_OPENED, "CASE", saved.getCaseId(),
                null, saved.getStatus().name(), ipAddress);
        return saved;
    }

    /** PATCH /api/cases/{id}/status : transitions + cloture + resultat d'enquete. */
    @Transactional
    public Case changeStatus(Long caseId, CaseStatusRequest request,
                             Long userId, String ipAddress) {
        Case entity = get(caseId);
        if (entity.getStatus().isTerminal()) {
            throw new ConflictException("Case cloturee : aucune mutation possible ("
                    + entity.getStatus().name() + ")");
        }
        CaseStatus target = request.status();
        CaseStatus previous = entity.getStatus();
        if (!previous.canMoveTo(target)) {
            throw new ConflictException("Transition de case illegale : "
                    + previous.name() + " -> " + target.name());
        }
        entity.setStatus(target);
        if (request.investigationResult() != null) {
            entity.setInvestigationResult(request.investigationResult());
        }
        if (target.isTerminal()) {
            entity.setClosedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        Case saved = caseRepository.save(entity);

        auditService.record(userId, AUDIT_CASE_STATUS_CHANGED, "CASE", saved.getCaseId(),
                previous.name(), target.name(), ipAddress);
        return saved;
    }

    /**
     * POST /api/cases/{caseId}/comments : le fil d'enquete.
     * userId est OBLIGATOIRE (l'attribut NOT NULL de case_comments) : pas de
     * pseudo-anonymat — sans authentification (M5), un commentaire sans
     * auteur est rejete en 400.
     */
    @Transactional
    public CaseComment addComment(Long caseId, String content, CommentType commentType,
                                  Long userId, String ipAddress) {
        Case entity = get(caseId);
        if (entity.getStatus().isTerminal()) {
            throw new ConflictException("Case cloturee : plus aucun commentaire ("
                    + entity.getStatus().name() + ")");
        }
        if (userId == null) {
            throw new BadRequestException("Header X-User-Id requis : un commentaire doit etre attribue");
        }
        CaseComment comment = new CaseComment();
        comment.setCaseId(caseId);
        comment.setUserId(userId);
        comment.setContent(content);
        if (commentType != null) {
            comment.setCommentType(commentType);
        }
        CaseComment saved = commentRepository.save(comment);

        // Trace rattachee a la CASE (pas a l'entite COMMENT) : la vue
        // « trace complete du dossier » (@api/audit?entityType=CASE&entityId=)
        // doit montrer chaque commentaire AU FIL de l'enquete.
        auditService.record(userId, AUDIT_COMMENT_ADDED, "CASE", caseId,
                null, saved.getCommentType().name(), ipAddress);
        return saved;
    }

    /** GET /api/cases/{id} — 404 si inconnue. */
    @Transactional(readOnly = true)
    public Case get(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException("caseId", caseId));
    }

    /** GET /api/alerts/{alertId}/case — la case unique de l'alerte (404 sinon). */
    @Transactional(readOnly = true)
    public Case forAlert(Long alertId) {
        return caseRepository.findByAlertId(alertId)
                .orElseThrow(() -> new CaseNotFoundException("alertId", alertId));
    }

    /** GET /api/cases/{id}/comments — fil chronologique. */
    @Transactional(readOnly = true)
    public List<CaseComment> comments(Long caseId) {
        get(caseId); // 404 si la case n'existe pas meme sans commentaire
        return commentRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
    }

    /** GET /api/cases — page, filtree par statut si fourni. */
    @Transactional(readOnly = true)
    public Page<Case> page(CaseStatus status, Pageable pageable) {
        return status == null
                ? caseRepository.findAllByOrderByOpenedAtDesc(pageable)
                : caseRepository.findByStatusOrderByOpenedAtDesc(status, pageable);
    }
}
