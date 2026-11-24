package tn.finrisk.alertcaseservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SmsRelayTestControllerTest {
    private static final String PHONE = "+21600000000";
    private static final String RELAY = "relay-test-token-used-only-in-this-test";
    private static final String OPERATOR = "operator-test-token-used-only-in-this-test";

    @Autowired JdbcTemplate jdbc;

    @Test
    void sendsOnlyAnAuthorizedJobOnceAndKeepsSubmissionIdempotent() {
        var controller = new SmsRelayTestController(jdbc, PHONE, RELAY, OPERATOR);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> controller.queue("Bearer " + RELAY, id))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(controller.queue("Bearer " + OPERATOR, id).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        controller.queue("Bearer " + OPERATOR, id);

        var relayAuth = "Bearer " + RELAY;
        var job = controller.claim(relayAuth).getBody();
        assertThat(job).isEqualTo(new SmsRelayTestController.PendingSms(
                id, PHONE, SmsRelayTestController.TEST_MESSAGE));
        assertThat(controller.claim(relayAuth).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.submitted(relayAuth, id).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.submitted(relayAuth, id).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT state FROM sms_relay_test_messages WHERE message_id=?",
                String.class, id)).isEqualTo("SUBMITTED");
    }
}
