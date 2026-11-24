package tn.finrisk.customerservice.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.finrisk.customerservice.domain.Customer;

/**
 * Acces a la table "customers".
 * Aucune requete ecrite ici : Spring Data la GENERE a partir du nom de la methode.
 *
 * Exemples derives :
 *   findByEmail(String)  ->  SELECT * FROM customers WHERE email = ?
 *   countByStatus(...)   ->  SELECT count(*) FROM customers WHERE status = ?
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    boolean existsByEmail(String email);
}
