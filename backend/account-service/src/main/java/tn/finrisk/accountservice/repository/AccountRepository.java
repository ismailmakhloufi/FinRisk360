package tn.finrisk.accountservice.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import tn.finrisk.accountservice.domain.Account;

/**
 * Acces a la table "accounts" (table propriaire de ce service - M0 §2).
 * Requetes derivees du nom, aucune ligne de SQL ecrite.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    /** Recherche parmi plusieurs numeros (resolution d'un RIB : le champ
     *  "compte" du RIB n'en garde que 11 des 12 chiffres -> 10 candidats). */
    Optional<Account> findByAccountNumberIn(Collection<String> accountNumbers);

    Page<Account> findByCustomerId(Long customerId, Pageable pageable);

    boolean existsByCustomerId(Long customerId);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockByAccountId(Long accountId);
}
