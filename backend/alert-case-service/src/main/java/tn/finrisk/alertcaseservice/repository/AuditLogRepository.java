package tn.finrisk.alertcaseservice.repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import tn.finrisk.alertcaseservice.domain.AuditLog;

/**
 * Acces a "audit_logs" — la trace exigee par le jalon M4.
 * Lecture paginee (Page<>) pour l'ecran d'audit ; les filtres derives
 * couvrent les usages M4 : par entite, par user, par action.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, Long entityId);

    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    long countByEntityTypeAndEntityId(String entityType, Long entityId);
}
