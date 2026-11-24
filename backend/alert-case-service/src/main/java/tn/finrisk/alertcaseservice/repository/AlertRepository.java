package tn.finrisk.alertcaseservice.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;

/**
 * Acces a "alerts".
 * findByAssessmentId : la regle « une seule alerte par evaluation » —
 * le doublon est rejete en service (409), l'index unique alert_code
 * reste le filet de securite final en base.
 */
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByAssessmentId(Long assessmentId);

    Optional<Alert> findByAlertCode(String alertCode);

    boolean existsByAssessmentId(Long assessmentId);

    Page<Alert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * File principale : TOUT sauf les alertes absorbees par un groupe
     * (ABSORBED). La 1re alerte du compte (OPEN) y figure, ses doublons
     * absorbes non — c'est le modele « 10 transactions -> 1 alerte ».
     */
    Page<Alert> findByStatusNotOrderByCreatedAtDesc(AlertStatus status, Pageable pageable);

    Page<Alert> findByStatusOrderByCreatedAtDesc(AlertStatus status, Pageable pageable);

    /** Alertes membres d'un groupe, dans l'ordre chronologique de creation. */
    List<Alert> findByGroupIdOrderByCreatedAtAsc(Long groupId);

    // ------------------------------------------------------------------
    // Dashboard (GET /api/alerts/stats) : les agregats sont calcules par
    // la BASE (GROUP BY), jamais en memoire sur des pages de 50 lignes.
    // ------------------------------------------------------------------

    @Query("SELECT a.status, COUNT(a) FROM Alert a GROUP BY a.status")
    List<Object[]> countGroupByStatus();

    @Query("SELECT a.severity, COUNT(a) FROM Alert a GROUP BY a.severity")
    List<Object[]> countGroupBySeverity();

    /** Cumul des alertes absorbees par un groupe (montant du « sac »). */
    @Query("SELECT SUM(a.amount) FROM Alert a WHERE a.status = "
            + "tn.finrisk.alertcaseservice.domain.enums.AlertStatus.ABSORBED")
    BigDecimal sumAbsorbedAmount();

    /** Activite quotidienne (7 derniers jours, jours UTC) pour la sparkline. */
    @Query(value = "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, "
            + "COUNT(*) AS cnt FROM alerts "
            + "WHERE created_at >= :since GROUP BY day ORDER BY day", nativeQuery = true)
    List<Object[]> countByDaySince(java.time.OffsetDateTime since);

    /** Alertes encore actives (non terminales) en severite haute/critique — banniere. */
    @Query("SELECT COUNT(a) FROM Alert a WHERE a.severity IN :severities "
            + "AND a.status NOT IN :terminal")
    long countActiveBySeverity(@Param("severities") Collection<Severity> severities,
            @Param("terminal") Collection<AlertStatus> terminal);

    /** Alertes cloturees (decision analyste : RESOLVED + FALSE_POSITIVE). */
    long countByStatusIn(Collection<AlertStatus> statuses);

    /** Duree moyenne de resolution en minutes (NULL si aucune alerte resolue). */
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 60.0) "
            + "FROM alerts WHERE resolved_at IS NOT NULL", nativeQuery = true)
    Double avgResolutionMinutes();
}
