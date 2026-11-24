package tn.finrisk.alertcaseservice.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import tn.finrisk.alertcaseservice.domain.CaseComment;

/**
 * Acces a "case_comments".
 * L'ordre chronologique du fil est porte par la requete derivee
 * (OrderByCreatedAtAsc) : le fil d'enquete se lit dans l'ordre d'ecriture.
 */
public interface CaseCommentRepository extends JpaRepository<CaseComment, Long> {

    List<CaseComment> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    long countByCaseId(Long caseId);
}
