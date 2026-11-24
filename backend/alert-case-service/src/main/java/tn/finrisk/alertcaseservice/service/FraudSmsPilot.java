package tn.finrisk.alertcaseservice.service;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tn.finrisk.alertcaseservice.client.AiClient;

/** Automatic pilot for the one phone number configured on the existing relay. */
@Service
@ConditionalOnProperty(name = "sms-relay.fraud-pilot-enabled", havingValue = "true")
class FraudSmsPilot {
    private static final Logger log = LoggerFactory.getLogger(FraudSmsPilot.class);
    private static final String SUSPECTED = "FRAUDE_SUSPECTE";

    private final JdbcTemplate jdbc;
    private final AiClient ai;
    private final JavaMailSender mail;
    private final String phone;
    private final String email;
    private final OffsetDateTime start;

    FraudSmsPilot(JdbcTemplate jdbc, AiClient ai, JavaMailSender mail,
            @Value("${sms-relay.test-enabled:false}") boolean relayEnabled,
            @Value("${sms-relay.test-recipient:}") String phone,
            @Value("${spring.mail.username:}") String email,
            @Value("${spring.mail.password:}") String appPassword,
            @Value("${sms-relay.fraud-pilot-start:}") String start) {
        if (!relayEnabled || !phone.matches("\\+216[0-9]{8}")
                || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || appPassword.isBlank()) {
            throw new IllegalArgumentException("Le pilote exige le relais de test, le mobile autorise et le compte courriel");
        }
        this.jdbc = jdbc;
        this.ai = ai;
        this.mail = mail;
        this.phone = phone;
        this.email = email;
        this.start = OffsetDateTime.parse(start);
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    void poll() {
        wakeIphone();
        var candidates = jdbc.query("""
                SELECT DISTINCT ON (t.transaction_id) t.transaction_id, al.alert_id
                FROM alerts al JOIN risk_assessments ra USING (assessment_id)
                JOIN transactions t USING (transaction_id)
                JOIN accounts a USING (account_id)
                JOIN customers c USING (customer_id)
                WHERE al.created_at >= ? AND t.status = 'PENDING'
                  AND al.status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS','ABSORBED')
                  AND c.phone ~ '^[+0-9 ().-]+$'
                  AND regexp_replace(c.phone, '[^0-9]', '', 'g') IN (?, ?, ?)
                  AND NOT EXISTS (SELECT 1 FROM sms_fraud_pilot_reviews r
                                  WHERE r.transaction_id = t.transaction_id)
                ORDER BY t.transaction_id, al.alert_id DESC LIMIT 10
                """, (row, index) -> new Candidate(row.getLong(1), row.getLong(2)),
                start, phone.substring(1), phone.substring(4), "00" + phone.substring(1));
        for (var candidate : candidates) {
            try {
                var assessment = ai.analyze(candidate.alertId());
                String verdict = assessment == null ? null : assessment.verdict();
                if (SUSPECTED.equals(verdict) || "NORMAL".equals(verdict) || "INCERTAIN".equals(verdict)) {
                    record(candidate, verdict);
                }
            } catch (RuntimeException error) {
                log.warn("Avis du pilote SMS indisponible pour l'alerte {} ({})",
                        candidate.alertId(), error.getClass().getSimpleName());
            }
        }
        if (!candidates.isEmpty()) {
            wakeIphone();
        }
    }

    private void record(Candidate candidate, String verdict) {
        jdbc.update("""
                WITH reviewed AS (
                    INSERT INTO sms_fraud_pilot_reviews (transaction_id, alert_id, ai_verdict)
                    VALUES (?, ?, ?) ON CONFLICT (transaction_id) DO NOTHING
                    RETURNING transaction_id, ai_verdict
                )
                INSERT INTO sms_relay_test_messages (message_id, recipient, state, fraud_transaction_id)
                SELECT ?, ?, 'QUEUED', transaction_id FROM reviewed
                WHERE ai_verdict = 'FRAUDE_SUSPECTE'
                ON CONFLICT (fraud_transaction_id) DO NOTHING
                """, candidate.transactionId(), candidate.alertId(), verdict, UUID.randomUUID(), phone);
    }

    private void wakeIphone() {
        var pending = jdbc.query("""
                UPDATE sms_relay_test_messages
                SET wake_attempts = wake_attempts + 1, wake_retry_at = now() + interval '2 minutes'
                WHERE message_id = (
                    SELECT message_id FROM sms_relay_test_messages
                    WHERE state='QUEUED' AND recipient=? AND fraud_transaction_id IS NOT NULL
                      AND wake_sent_at IS NULL AND wake_attempts < 5
                      AND (wake_retry_at IS NULL OR wake_retry_at <= now())
                    ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING message_id, wake_attempts
                """, (row, index) -> new Wake(row.getObject(1, UUID.class), row.getInt(2)), phone);
        if (pending.isEmpty()) {
            return;
        }
        var wake = pending.getFirst();
        var signal = new SimpleMailMessage();
        signal.setFrom(email);
        signal.setTo(email);
        signal.setSubject("Test finrisk360");
        signal.setText("Un message de test est en attente dans FinRisk360.");
        try {
            mail.send(signal);
        } catch (RuntimeException error) {
            log.warn("Courriel pilote SMS indisponible, tentative {} ({})",
                    wake.attempt(), error.getClass().getSimpleName());
            return;
        }
        jdbc.update("UPDATE sms_relay_test_messages SET wake_sent_at=now() WHERE message_id=?", wake.id());
    }

    private record Candidate(long transactionId, long alertId) {}
    private record Wake(UUID id, int attempt) {}
}
