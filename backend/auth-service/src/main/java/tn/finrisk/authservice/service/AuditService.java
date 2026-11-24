package tn.finrisk.authservice.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.authservice.domain.AuditLog;
import tn.finrisk.authservice.repository.AuditLogRepository;

/**
 * Ecriture des traces AUTH du jalon M5 (LOGIN_SUCCESS / LOGIN_FAILED /
 * USER_CREATED / USER_UPDATED) dans audit_logs — la meme table que
 * alert-case-service, qui reste le lecteur unique (GET /api/audit).
 *
 * Propagation MANDATORY (meme contrat que M4 §trace) : la trace n'est
 * ecrite que DANS la transaction de la mutation qu'elle documente ; appelee
 * hors transaction, Spring leverait une exception plutot que de laisser une
 * trace orpheline desolee du changement qu'elle est censee attester.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * @param userId     auteur JWT (sub) ; null pour un login echoue sur un
     *                   username qui n'existe pas (user_id nullable)
     * @param action     LOGIN_SUCCESS | LOGIN_FAILED | USER_CREATED | USER_UPDATED
     * @param entityType toujours "USER" pour le domaine de l'auth-service
     * @param entityId   concerne (0 si inconnu ; colonne NOT NULL, pas une FK)
     * @param oldValue   etat avant (mutations), null sinon
     * @param newValue   resume non sensible (role, username...) — JAMAIS de
     *                   mot de passe ni de hash dans la trace
     * @param ipAddress  X-Forwarded-For injecte par le gateway
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
     * Trace d'un REFUS (LOGIN_FAILED) : elle doit SURVIVRE au rollback.
     * Quand login() leve le 401, Spring annule toute la transaction — et la
     * trace MANDATORY ecrite dedans partirait avec elle, effacant la preuve
     * de la tentative. REQUIRES_NEW ouvre donc une micro-transaction qui
     * COMMITTE des que recordRefus rend la main : le refus est annule, la
     * trace de tentative, non.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefus(Long userId, String action, String entityType, Long entityId,
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
}
