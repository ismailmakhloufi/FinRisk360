package tn.finrisk.alertcaseservice.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;

/**
 * Rapport de l'agent nocturne : lecture pour le retour de l'analyste
 * le matin (derniere execution d'abord) + derniere execution connue.
 */
public interface NightAgentDecisionRepository extends JpaRepository<NightAgentDecision, Long> {

    Page<NightAgentDecision> findByOrderByRunAtDescDecisionIdDesc(Pageable pageable);

    Optional<NightAgentDecision> findTopByOrderByRunAtDescDecisionIdDesc();

    /**
     * Une tx deja presente au rapport n'est pas re-jugee : chaque
     * transaction n'a qu'UNE ligne (sinon chaque tick horaire ajoute le
     * meme « LAISSE » et le rapport devient du bruit pour l'analyste).
     */
    boolean existsByTransactionId(Long transactionId);
}
