package tn.finrisk.transactionservice.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.transactionservice.web.error.TransactionNotModifiableException;

/** Marqueur durable, volontairement distinct de la transaction metier. */
@Service
public class FinancialAttemptGuard {
    private final JdbcTemplate jdbc;

    public FinancialAttemptGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tentative en cours, avec la cible visee (COMPLETED ou CANCELLED). */
    public record Pending(String target) {
    }

    /**
     * Lit l'etat du marqueur SANS le modifier (hors transaction metier) :
     * le decideur (changeStatus) choisit ensuite entre reprise idempotente
     * (MEME cible) et blocage pour reconciliation (toute autre cible).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.Optional<Pending> pending(Long id) {
        java.util.List<String> targets = jdbc.queryForList(
                "SELECT target_status FROM financial_attempt_guard "
                        + "WHERE transaction_id = ? AND state = 'IN_PROGRESS'",
                String.class, id);
        return targets.stream().findFirst().map(Pending::new);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(Long id, String target) {
        int count = jdbc.update("INSERT INTO financial_attempt_guard(transaction_id, state, target_status) "
                + "VALUES (?, 'IN_PROGRESS', ?) ON CONFLICT (transaction_id) DO UPDATE "
                + "SET state = 'IN_PROGRESS', target_status = EXCLUDED.target_status, updated_at = now() "
                + "WHERE financial_attempt_guard.state = 'FINISHED'", id, target);
        if (count != 1) {
            throw new TransactionNotModifiableException(id, "MOUVEMENT_INCERTAIN_RECONCILIATION_REQUISE");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long id) {
        jdbc.update("UPDATE financial_attempt_guard SET state = 'FINISHED', updated_at = now() "
                + "WHERE transaction_id = ? AND state = 'IN_PROGRESS'", id);
    }
}
