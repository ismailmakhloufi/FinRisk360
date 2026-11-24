package tn.finrisk.authservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import tn.finrisk.authservice.domain.AuditLog;

/** Ecriture seule cote auth-service : la LECTURE du journal se fait par
 *  alert-case-service (GET /api/audit, ownership M0 §2 — on ne lit pas la
 *  table d'autrui directement). Seuls des select utilitaires pour les tests. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
}
