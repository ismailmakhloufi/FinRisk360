package tn.finrisk.riskservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tn.finrisk.riskservice.domain.RiskAssessment;

/**
 * Acces a la table "risk_assessments" (NOTRE table) : les verdicts, en ecriture SEULE.
 * L'historique d'une transaction = toutes ses evaluations, la plus recente en premier.
 */
@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    List<RiskAssessment> findByTransactionIdOrderByAssessedAtDesc(Long transactionId);

    // ------------------------------------------------------------------
    // Dashboard (GET /api/evaluations/stats) : agregats cote base.
    // ------------------------------------------------------------------

    @Query("SELECT a.decision, COUNT(a) FROM RiskAssessment a GROUP BY a.decision")
    List<Object[]> countGroupByDecision();

    @Query("SELECT a.riskLevel, COUNT(a) FROM RiskAssessment a GROUP BY a.riskLevel")
    List<Object[]> countGroupByRiskLevel();

    /** Moyenne et max des scores — deux requetes scalaires separees :
     *  une double agregation JPQL renvoie un Object[] imbrique (ClassCastException
     *  si on la caste en Number). Null si aucune evaluation. */
    @Query("SELECT AVG(a.riskScore) FROM RiskAssessment a")
    Number avgRiskScore();

    @Query("SELECT MAX(a.riskScore) FROM RiskAssessment a")
    Number maxRiskScore();
}
