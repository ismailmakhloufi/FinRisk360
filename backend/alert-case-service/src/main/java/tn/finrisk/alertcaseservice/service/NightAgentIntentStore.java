package tn.finrisk.alertcaseservice.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance durable d'une intention, distincte de la decision effective.
 * Ne prouve pas qu'un appel financier a reussi ni qui l'a execute.
 */
@Service
public class NightAgentIntentStore {
    private final JdbcTemplate jdbc;

    public NightAgentIntentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Intent(long transactionId, UUID intentId, long assessmentId, long alertId,
            BigDecimal amount, BigDecimal riskScore, String riskLevel, String engineDecision,
            String aiVerdict, Double aiConfidence, String agentReason, String state) { }

    /** Enregistre avant l'appel distant et ne remplace jamais une intention existante. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Intent prepare(Intent intent) {
        if (!"PREPARED".equals(intent.state())) {
            throw new IllegalArgumentException("Une nouvelle intention doit etre PREPARED");
        }
        jdbc.update("INSERT INTO night_agent_validation_intents "
                + "(transaction_id,intent_id,assessment_id,alert_id,amount,risk_score,risk_level,"
                + "engine_decision,ai_verdict,ai_confidence,agent_reason,state) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,'PREPARED') ON CONFLICT (transaction_id) DO NOTHING",
                intent.transactionId(), intent.intentId(), intent.assessmentId(), intent.alertId(),
                intent.amount(), intent.riskScore(), intent.riskLevel(), intent.engineDecision(),
                intent.aiVerdict(), intent.aiConfidence(), intent.agentReason());
        Intent stored = find(intent.transactionId());
        Integer matching = jdbc.queryForObject("SELECT count(*) FROM night_agent_validation_intents "
                + "WHERE transaction_id=? AND intent_id=? AND assessment_id=? AND alert_id=? "
                + "AND amount=? AND risk_score IS NOT DISTINCT FROM ? AND risk_level=? "
                + "AND engine_decision IS NOT DISTINCT FROM ? AND ai_verdict=? "
                + "AND ai_confidence IS NOT DISTINCT FROM ? AND agent_reason=?", Integer.class,
                intent.transactionId(), intent.intentId(), intent.assessmentId(), intent.alertId(),
                intent.amount(), intent.riskScore(), intent.riskLevel(), intent.engineDecision(),
                intent.aiVerdict(), intent.aiConfidence(), intent.agentReason());
        if (matching == null || matching != 1) {
            throw new IllegalStateException("Intention existante differente pour cette transaction");
        }
        return stored;
    }

    @Transactional(readOnly = true)
    public Intent find(long transactionId) {
        return jdbc.queryForObject("SELECT * FROM night_agent_validation_intents WHERE transaction_id=?",
                this::map, transactionId);
    }
    @Transactional(readOnly = true)
    public java.util.List<Intent> prepared(int limit) {
        return jdbc.query("SELECT * FROM night_agent_validation_intents WHERE state = 'PREPARED'"
                + " ORDER BY completed_at IS NULL, updated_at, transaction_id LIMIT ?", this::map, limit);
    }

    @Transactional(readOnly = true)
    public boolean completed(Intent intent) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM night_agent_validation_intents "
                        + "WHERE transaction_id = ? AND intent_id = ?",
                Boolean.class, intent.transactionId(), intent.intentId()));
    }

    public boolean finalizeCompleted(Intent intent) {
        return jdbc.update("UPDATE night_agent_validation_intents SET state = 'FINALIZED', updated_at = now()"
                + " WHERE transaction_id = ? AND intent_id = ? AND state = 'PREPARED'"
                + " AND completed_at IS NOT NULL", intent.transactionId(), intent.intentId()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(Intent intent) {
        jdbc.update("UPDATE night_agent_validation_intents SET updated_at = now() "
                + "WHERE transaction_id = ? AND state = 'PREPARED'", intent.transactionId());
    }

    @Transactional(readOnly = true)
    public boolean exists(long transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM night_agent_validation_intents WHERE transaction_id = ?)",
                Boolean.class, transactionId));
    }

    private Intent map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Intent(rs.getLong("transaction_id"),
                        rs.getObject("intent_id", UUID.class), rs.getLong("assessment_id"),
                        rs.getLong("alert_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("risk_score"),
                        rs.getString("risk_level"), rs.getString("engine_decision"),
                        rs.getString("ai_verdict"), rs.getObject("ai_confidence", Double.class),
                        rs.getString("agent_reason"), rs.getString("state"));
    }
}
