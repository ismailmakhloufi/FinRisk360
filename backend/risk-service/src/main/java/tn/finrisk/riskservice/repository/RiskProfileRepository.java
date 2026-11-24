package tn.finrisk.riskservice.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.finrisk.riskservice.domain.RiskProfile;

/**
 * Acces a la table "risk_profiles" (NOTRE table depuis l'amendement M3).
 * Un seul profil par client (contrainte UNIQUE customer_id).
 */
@Repository
public interface RiskProfileRepository extends JpaRepository<RiskProfile, Long> {

    Optional<RiskProfile> findByCustomerId(Long customerId);
}
