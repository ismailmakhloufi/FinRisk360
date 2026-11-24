package tn.finrisk.riskservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.finrisk.riskservice.domain.RiskFactor;

/**
 * Acces a la table "risk_factors" (NOTRE table) : les facteurs explicatifs
 * d'un assessment (ce qui rend le score lisible).
 */
@Repository
public interface RiskFactorRepository extends JpaRepository<RiskFactor, Long> {

    List<RiskFactor> findByAssessmentId(Long assessmentId);
}
