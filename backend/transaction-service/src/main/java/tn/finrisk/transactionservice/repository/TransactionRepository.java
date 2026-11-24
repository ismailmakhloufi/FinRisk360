package tn.finrisk.transactionservice.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.finrisk.transactionservice.domain.Transaction;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;

/**
 * Acces a la table "transactions" (table propriaire de ce service).
 * Filtres dynamiques (compte, statut, date "since") : JpaSpecificationExecutor
 * evite de multiplier les methodes derivees (accountId x status x since = 8 variantes).
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>,
        JpaSpecificationExecutor<Transaction> {

    /** Serialise les changements de statut concurrents avant tout mouvement de solde. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.transactionId = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

    Page<Transaction> findByAccountId(Long accountId, Pageable pageable);

    long countByAccountId(Long accountId);

    // ------------------------------------------------------------------
    // Dashboard (GET /api/transactions/stats) : agregats cote base.
    // ------------------------------------------------------------------

    /** Top beneficiaires par nombre de transactions (limite par Pageable). */
    @Query("SELECT t.beneficiaryName, COUNT(t), COALESCE(SUM(t.amount), 0) "
            + "FROM Transaction t WHERE t.beneficiaryName IS NOT NULL "
            + "GROUP BY t.beneficiaryName ORDER BY COUNT(t) DESC")
    List<Object[]> topBeneficiaries(Pageable pageable);

    /** Activite quotidienne (jours UTC) + volume pour la sparkline du dashboard. */
    @Query(value = "SELECT to_char(transaction_date AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, "
            + "COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS vol "
            + "FROM transactions WHERE transaction_date >= :since "
            + "GROUP BY day ORDER BY day", nativeQuery = true)
    List<Object[]> statsByDaySince(OffsetDateTime since);

    /** Volume total sur une fenetre [from, to) — tendance vs periode precedente. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.transactionDate >= :from AND t.transactionDate < :to")
    BigDecimal sumBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** Repartition des transactions par type (TRANSFER, PAYMENT...). */
    @Query("SELECT t.transactionType, COUNT(t) FROM Transaction t "
            + "GROUP BY t.transactionType ORDER BY COUNT(t) DESC")
    List<Object[]> countGroupByTransactionType();

    /** Repartition des transactions par canal (MOBILE, WEB, ATM...). */
    @Query("SELECT t.channel, COUNT(t) FROM Transaction t "
            + "GROUP BY t.channel ORDER BY COUNT(t) DESC")
    List<Object[]> countGroupByChannel();
}
