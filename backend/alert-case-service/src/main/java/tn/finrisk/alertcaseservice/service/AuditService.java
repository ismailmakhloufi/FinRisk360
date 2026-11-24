package tn.finrisk.alertcaseservice.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.domain.AuditLog;
import tn.finrisk.alertcaseservice.repository.AuditLogRepository;
import tn.finrisk.alertcaseservice.web.dto.AuditResponse;
import java.util.List;

/**
 * Ecriture de la TRACE (audit_logs) exigee par le jalon M4 :
 * « alerte -> case -> commentaire -> cloture tracee ».
 *
 * Propagation MANDATORY : cette methode ne peut etre appelee que DANS la
 * transaction de la mutation qu'elle trace. Si quelqu'un l'appelait hors
 * transaction, Spring leverait une exception plutot que d'ecrire une trace
 * orpheline (la trace et le changement doivent etre atomiques : soit les
 * deux, soit rien — c'est le meme commit).
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Trace une mutation.
     *
     * @param userId    auteur (header X-User-Id, nullable tant que M5 est absent)
     * @param action    ex. ALERT_CREATED, CASE_STATUS_CHANGED, COMMENT_ADDED
     * @param entityType ex. ALERT, CASE, CASE_COMMENT
     * @param oldValue  avant (ex. OPEN), null sur une creation
     * @param newValue  apres (ex. ACKNOWLEDGED)
     * @param ipAddress header X-Forwarded-For si present, sinon null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long userId, String action, String entityType, Long entityId,
                       String oldValue, String newValue, String ipAddress) {
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        entry.setIpAddress(ipAddress);
        auditLogRepository.save(entry);
    }

    /**
     * GET /api/audit — le journal, du plus recent au plus ancien.
     * Filtres optionnels : userId ou action (l'un ou l'autre, sinon tout).
     */
    @Transactional(readOnly = true)
    public Page<AuditResponse> page(Long userId, String action, Pageable pageable) {
        if (userId != null) {
            return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                    .map(AuditResponse::from);
        }
        if (action != null) {
            return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable)
                    .map(AuditResponse::from);
        }
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(AuditResponse::from);
    }

    /** GET /api/audit?entityType=&entityId= — la trace COMPLETE d'une entite
     *  (c'est la vue « que s'est-il passe sur ce dossier ? » de la soutenance). */
    @Transactional(readOnly = true)
    public List<AuditResponse> byEntity(String entityType, Long entityId) {
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc(entityType, entityId)
                .stream()
                .map(AuditResponse::from)
                .toList();
    }
}
