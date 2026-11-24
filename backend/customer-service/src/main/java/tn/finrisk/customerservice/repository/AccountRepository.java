package tn.finrisk.customerservice.repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.finrisk.customerservice.domain.Account;

/**
 * Acces a la table "accounts".
 * La "traversee" de la FK se note avec le chemin du champ :
 *   findByCustomerCustomerId  ->  ... WHERE customer_id = ?  (customer -> customer_id)
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByCustomerCustomerIdOrderByAccountIdDesc(Long customerId);

    Page<Account> findByCustomerCustomerId(Long customerId, Pageable pageable);

    boolean existsByCustomerCustomerId(Long customerId);
}
