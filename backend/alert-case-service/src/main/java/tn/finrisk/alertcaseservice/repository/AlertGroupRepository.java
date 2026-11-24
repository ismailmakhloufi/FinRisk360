package tn.finrisk.alertcaseservice.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.finrisk.alertcaseservice.domain.AlertGroup;
import tn.finrisk.alertcaseservice.domain.enums.GroupScope;
import tn.finrisk.alertcaseservice.domain.enums.GroupStatus;

/**
 * Groupes d'alertes.
 *
 * L'INVARIANT : UN SEUL groupe ouvert par perimetre. Il est garanti par un
 * index unique PARTIEL (scope_type, scope_value) WHERE status='OPEN', pose par
 * la migration 07 — pas par un verrou applicatif.
 *
 * Le cas d'usage EST une rafale : 10 virements fractionnes sont evalues quasi
 * en parallele. Trois mecanismes rendent l'agregation correcte SANS jamais
 * bloquer une connexion (un verrou tenu pendant une rafale vide le pool et
 * fait echouer les alertes — comportement mesure et rejete) :
 *
 *   1. closeExpiredOpenGroup  : ferme (idempotent) le groupe dont la fenetre
 *      est passee, pour liberer le slot « ouvert » du perimetre ;
 *   2. insertOpenGroupIfAbsent: INSERT ... ON CONFLICT DO NOTHING — si un
 *      concurrent a deja cree le groupe, on n'echoue PAS (contrairement a un
 *      INSERT nu, qui aborterait la transaction PostgreSQL et tuerait l'alerte) ;
 *   3. incrementAndEscalate  : UPDATE atomique ... RETURNING — pas de
 *      lecture-modification-ecriture en Java, donc pas de conflit
 *      d'optimistic locking a faire echouer.
 */
public interface AlertGroupRepository extends JpaRepository<AlertGroup, Long> {

    /** Projection du RETURNING de incrementAndEscalate. */
    interface GroupAggregateRow {
        int getRelatedCount();

        BigDecimal getTotalAmount();

        String getSeverity();
    }

    /**
     * Projection NON GEREE du groupe ouvert : group_id, severite, customer_id.
     *
     * On ne charge JAMAIS l'entite AlertGroup dans le chemin d'ecriture
     * (attach) : une entite managee serait « dirty-checked » a la fin de la
     * transaction, ce qui declencherait un UPDATE avec verrou optimiste
     * (@Version) — et sous rafale deux transactions ecriraient la meme ligne,
     * l'une echouerait avec StaleObjectStateException et PERDRAIT son alerte.
     * Toutes les ecritures sont donc des natives queries atomiques.
     */
    interface OpenGroupRow {
        Long getGroupId();

        String getSeverity();

        Long getCustomerId();
    }

    /** Le groupe ouvert du perimetre, en projection legere (non managee). */
    @Query(value = """
            SELECT group_id AS "groupId", severity AS severity,
                   customer_id AS "customerId"
            FROM alert_groups
            WHERE scope_type = :scopeType
              AND scope_value = :scopeValue
              AND status = 'OPEN'
            """, nativeQuery = true)
    Optional<OpenGroupRow> findOpenGroupRow(@Param("scopeType") String scopeType,
                                            @Param("scopeValue") String scopeValue);

    /**
     * Renseigne le client emetteur si personne ne l'a encore fait. Idempotent
     * et sans verrou optimiste : un concurrent qui gagne la course ne provoque
     * aucune erreur, et une entite managee n'est jamais sale par cette ecriture.
     */
    @Modifying
    @Query(value = """
            UPDATE alert_groups
            SET customer_id = :customerId, updated_at = now()
            WHERE group_id = :groupId AND customer_id IS NULL
            """, nativeQuery = true)
    int setCustomerIdIfAbsent(@Param("groupId") Long groupId,
                              @Param("customerId") Long customerId);

    /**
     * Ferme le groupe ouvert DONT LA FENETRE EST PASSEE (libere le slot pour
     * un nouvel episode). Idempotent : un concurrent qui ferme le meme groupe
     * ne provoque aucune erreur. Aucune ligne si la fenetre est encore valide.
     */
    @Modifying
    @Query(value = """
            UPDATE alert_groups
            SET status = 'RESOLVED', updated_at = now()
            WHERE scope_type = :scopeType
              AND scope_value = :scopeValue
              AND status = 'OPEN'
              AND window_start + make_interval(hours => window_hours) <= :now
            """, nativeQuery = true)
    int closeExpiredOpenGroup(@Param("scopeType") String scopeType,
                              @Param("scopeValue") String scopeValue,
                              @Param("now") OffsetDateTime now);

    /**
     * Ouvre le groupe du perimetre s'il n'en existe aucun. ON CONFLICT DO
     * NOTHING contre l'index partiel : si un concurrent a gagne la course,
     * on retourne NULL sans aborter la transaction (l'alerte reste valide).
     *
     * @return le group_id cree, ou NULL si un concurrent l'a deja cree
     */
    @Query(value = """
            INSERT INTO alert_groups
                (scope_type, scope_value, window_start, window_hours,
                 severity, status, related_count, total_amount, version)
            VALUES (:scopeType, :scopeValue, :windowStart, :windowHours,
                    :severity, 'OPEN', 0, 0, 0)
            ON CONFLICT (scope_type, scope_value) WHERE status = 'OPEN'
            DO NOTHING
            RETURNING group_id
            """, nativeQuery = true)
    Long insertOpenGroupIfAbsent(@Param("scopeType") String scopeType,
                                 @Param("scopeValue") String scopeValue,
                                 @Param("windowStart") OffsetDateTime windowStart,
                                 @Param("windowHours") int windowHours,
                                 @Param("severity") String severity);

    /**
     * Incremente le compteur et le cumul, et RECALCULE la severite agregee,
     * en UNE seule statement : aucune lecture-modification-ecriture, donc
     * aucun conflit d'optimistic locking meme sous rafale.
     *
     * severite = MAX( severite courante, severite de l'alerte, plancher du
     * compteur ). Le plancher est le miroir exact de
     * AlertGroupService.withCountFloor (>= :med -> MEDIUM, >= :high -> HIGH).
     */
    @Query(value = """
            UPDATE alert_groups
            SET related_count = related_count + 1,
                total_amount  = total_amount + :amount,
                severity = (
                    SELECT CASE ranks.rnk
                               WHEN 4 THEN 'CRITICAL'
                               WHEN 3 THEN 'HIGH'
                               WHEN 2 THEN 'MEDIUM'
                               ELSE 'LOW'
                           END
                    FROM (SELECT GREATEST(
                              CASE alert_groups.severity
                                  WHEN 'CRITICAL' THEN 4
                                  WHEN 'HIGH' THEN 3
                                  WHEN 'MEDIUM' THEN 2
                                  ELSE 1 END,
                              CASE :severity
                                  WHEN 'CRITICAL' THEN 4
                                  WHEN 'HIGH' THEN 3
                                  WHEN 'MEDIUM' THEN 2
                                  ELSE 1 END,
                              CASE
                                  WHEN alert_groups.related_count + 1 >= :highAt THEN 3
                                  WHEN alert_groups.related_count + 1 >= :mediumAt THEN 2
                                  ELSE 1 END
                          ) AS rnk) ranks
                ),
                updated_at = now()
            WHERE group_id = :groupId
            RETURNING related_count AS "relatedCount",
                      total_amount AS "totalAmount",
                      severity AS severity
            """, nativeQuery = true)
    GroupAggregateRow incrementAndEscalate(@Param("groupId") Long groupId,
                                           @Param("amount") BigDecimal amount,
                                           @Param("severity") String severity,
                                           @Param("mediumAt") int mediumAt,
                                           @Param("highAt") int highAt);

    /** Groupes ouverts, du plus fraichement mis a jour au plus ancien. */
    List<AlertGroup> findByStatusOrderByUpdatedAtDesc(GroupStatus status);

    /** Groupes d'un client (purge DELETE /api/alerts?customerId=). */
    List<AlertGroup> findByCustomerId(Long customerId);

    /**
     * Cloture le groupe s'il est encore OUVERT (decision de l'analyste :
     * RESOLVED = pattern accepte, REFUSED = fraude confirmee). Atomique et
     * idempotent : le premier decisionnaire gagne (retourne 1), un second
     * echoue silencieusement (retourne 0 -> 409 cote service). Pas de
     * verrou optimiste : l'ecriture est une native query, comme tout le
     * reste de ce repository.
     *
     * @return 1 si le groupe etait encore OPEN, 0 sinon
     */
    @Modifying
    @Query(value = """
            UPDATE alert_groups
            SET status = :status, updated_at = now()
            WHERE group_id = :groupId AND status = 'OPEN'
            """, nativeQuery = true)
    int closeGroupIfOpen(@Param("groupId") Long groupId, @Param("status") String status);
}
