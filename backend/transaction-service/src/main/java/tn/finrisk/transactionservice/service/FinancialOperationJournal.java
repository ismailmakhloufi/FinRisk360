package tn.finrisk.transactionservice.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Journal durable d'une etape ; aucun appel distant ne se produit ici. */
@Service
public class FinancialOperationJournal {

    public static final String STATE_CONFIRMED = "CONFIRMED";

    private final JdbcTemplate jdbc;

    public FinancialOperationJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(UUID operationId, String state) { }

    public boolean isConfirmed(Entry entry) {
        return STATE_CONFIRMED.equals(entry.state());
    }

    /**
     * Identifiant de tentative STABLE par transaction : la meme cle est
     * recalculee a chaque rejeu (reprise apres panne, re-tick de l'agent),
     * ce qui rend le rejeu inoffensif cote account-service.
     */
    public static UUID attemptFor(long transactionId) {
        return UUID.nameUUIDFromBytes(
                ("finrisk-financial-tx:" + transactionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Une compensation (remboursement apres echec) rend l'etat reel du solde
     * impossible a deduire du journal : aucun rejeu n'est autorise alors,
     * meme pour la meme cible — reconciliation humaine.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean hasCompensation(UUID attempt) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM financial_operation_journal "
                        + "WHERE attempt_id = ? AND step IN ('COMPENSATE_SOURCE', 'REVERSE_COMPENSATION')",
                Integer.class, attempt);
        return count != null && count > 0;
    }

    /**
     * Libere definitivement une tentative dont l'etat net est CONNU nul
     * (compensation confirmee) : les cles etantes annulees, le prochain essai
     * repart de zero avec de nouvelles cles — sans double mouvement puisque
     * l'operation annulee a ete appliquee puis reversee.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(UUID attempt) {
        jdbc.update("DELETE FROM financial_operation_journal WHERE attempt_id = ?", attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entry prepare(UUID attempt, String step, long transactionId, long accountId,
            BigDecimal amount, String direction) {
        UUID operation = UUID.randomUUID();
        jdbc.update("INSERT INTO financial_operation_journal "
                + "(attempt_id,step,operation_id,transaction_id,account_id,amount,direction,state) "
                + "VALUES (?,?,?,?,?,?,?,'PREPARED') ON CONFLICT (attempt_id,step) DO NOTHING",
                attempt, step, operation, transactionId, accountId, amount, direction);
        var row = jdbc.queryForMap("SELECT operation_id,transaction_id,account_id,amount,direction,state "
                + "FROM financial_operation_journal WHERE attempt_id=? AND step=?", attempt, step);
        if (((Number) row.get("transaction_id")).longValue() != transactionId
                || ((Number) row.get("account_id")).longValue() != accountId
                || ((BigDecimal) row.get("amount")).compareTo(amount) != 0
                || !row.get("direction").equals(direction)) {
            throw new IllegalStateException("Etape financiere reutilisee avec des donnees differentes");
        }
        return new Entry((UUID) row.get("operation_id"), (String) row.get("state"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirm(UUID attempt, String step, UUID operationId) {
        int changed = jdbc.update("UPDATE financial_operation_journal SET state='CONFIRMED', updated_at=now() "
                + "WHERE attempt_id=? AND step=? AND operation_id=?", attempt, step, operationId);
        if (changed != 1) {
            throw new IllegalStateException("Etape financiere introuvable ou cle incorrecte");
        }
    }
}
