package tn.finrisk.alertcaseservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.finrisk.alertcaseservice.domain.Case;
import tn.finrisk.alertcaseservice.domain.enums.CaseStatus;

/**
 * Acces a "cases".
 * findByAlertId : 1 case = 1 alerte (uq_cases_alert) — le second ouvre
 * d'une meme alerte est rejete en service (409).
 */
public interface CaseRepository extends JpaRepository<Case, Long> {

    Optional<Case> findByAlertId(Long alertId);

    boolean existsByAlertId(Long alertId);

    Page<Case> findAllByOrderByOpenedAtDesc(Pageable pageable);

    Page<Case> findByStatusOrderByOpenedAtDesc(CaseStatus status, Pageable pageable);

    /** Dashboard : dossiers par statut (calcules par la base). */
    @Query("SELECT c.status, COUNT(c) FROM Case c GROUP BY c.status")
    List<Object[]> countGroupByStatus();
}
