package tn.finrisk.transactionservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.finrisk.transactionservice.domain.Merchant;

/** Acces a la table "merchants" (table propriaire de ce service). */
@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    boolean existsByMerchantNameIgnoreCase(String merchantName);
}
