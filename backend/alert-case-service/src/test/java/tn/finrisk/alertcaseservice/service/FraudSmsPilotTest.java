package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.client.AiClient;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FraudSmsPilotTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void oneSuspectTransactionQueuesOneSmsAndEmailsOnlyTheOperator() {
        var ai = mock(AiClient.class);
        var mail = mock(JavaMailSender.class);
        var start = OffsetDateTime.now().minusMinutes(1);
        var pilot = new FraudSmsPilot(jdbc, ai, mail, true, "+21600000042",
                "operator@example.test", "test-only", start.toString());
        long suspect = alert("00 000 042", start.plusSeconds(10));
        long ordinary = alert("+216 00 000 042", start.plusSeconds(10));
        long unrelated = alert("+21600000055", start.plusSeconds(10));
        long older = alert("+21600000042", start.minusSeconds(10));
        when(ai.analyze(suspect)).thenReturn(new AiAnalysisView(suspect, "FRAUDE_SUSPECTE", 0.9, ""));
        when(ai.analyze(ordinary)).thenReturn(new AiAnalysisView(ordinary, "NORMAL", 0.9, ""));

        pilot.poll();
        pilot.poll();

        verify(ai, times(1)).analyze(suspect);
        verify(ai, times(1)).analyze(ordinary);
        verify(ai, never()).analyze(unrelated);
        verify(ai, never()).analyze(older);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sms_relay_test_messages m
                JOIN sms_fraud_pilot_reviews r ON r.transaction_id=m.fraud_transaction_id
                WHERE r.alert_id=? AND m.state='QUEUED' AND m.recipient=? AND m.wake_sent_at IS NOT NULL
                """, Integer.class, suspect, "+21600000042")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sms_relay_test_messages m
                JOIN sms_fraud_pilot_reviews r ON r.transaction_id=m.fraud_transaction_id
                WHERE r.alert_id=?
                """, Integer.class, ordinary)).isZero();
        var signal = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(1)).send(signal.capture());
        assertThat(signal.getValue().getTo()).containsExactly("operator@example.test");
        assertThat(signal.getValue().getSubject()).isEqualTo("Test finrisk360");
        assertThat(signal.getValue().getText()).doesNotContain("+21600000042");
    }

    private long alert(String phone, OffsetDateTime date) {
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (first_name,last_name,phone) VALUES ('Test','SMS',?)
                RETURNING customer_id
                """, Long.class, phone);
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (customer_id,account_number,account_type) VALUES (?,?,'CHECKING')
                RETURNING account_id
                """, Long.class, customer, code());
        long transaction = jdbc.queryForObject("""
                INSERT INTO transactions (account_id,amount,transaction_type,country)
                VALUES (?,10,'TRANSFER','TN') RETURNING transaction_id
                """, Long.class, account);
        long assessment = jdbc.queryForObject("""
                INSERT INTO risk_assessments (transaction_id,risk_score,risk_level,decision)
                VALUES (?,60,'HIGH','PENDING_REVIEW') RETURNING assessment_id
                """, Long.class, transaction);
        return jdbc.queryForObject("""
                INSERT INTO alerts (assessment_id,alert_code,alert_type,severity,created_at)
                VALUES (?,?,'OTHER','HIGH',?) RETURNING alert_id
                """, Long.class, assessment, code(), date);
    }

    private String code() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
