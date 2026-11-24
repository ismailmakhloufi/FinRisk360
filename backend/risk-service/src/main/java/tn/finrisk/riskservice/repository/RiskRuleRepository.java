package tn.finrisk.riskservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.finrisk.riskservice.domain.RiskRule;

/**
 * Acces a la table "risk_rules" (NOTRE table) : les regles du moteur,
 * modifiables sans redemarrer (enabled, poids, seuil).
 */
@Repository
public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {

    /** Les regles que le moteur doit evaluer, dans un ordre deterministe (RULE-001, 002...). */
    List<RiskRule> findByEnabledTrueOrderByRuleCode();

    Optional<RiskRule> findByRuleCode(String ruleCode);
}
