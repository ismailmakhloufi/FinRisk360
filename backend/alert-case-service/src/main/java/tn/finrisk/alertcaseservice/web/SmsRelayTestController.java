package tn.finrisk.alertcaseservice.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/sms-relay")
@ConditionalOnProperty(name = "sms-relay.test-enabled", havingValue = "true")
public class SmsRelayTestController {
    private static final String ALERT_MESSAGE = "Atlas Bank - Une operation inhabituelle a ete detectee. "
            + "Consultez vos operations dans votre espace Atlas Bank habituel.";
    static final String TEST_MESSAGE = "TEST - " + ALERT_MESSAGE;

    private final JdbcTemplate jdbc;
    private final String recipient;
    private final String relayToken;
    private final String operatorToken;

    public SmsRelayTestController(JdbcTemplate jdbc,
            @Value("${sms-relay.test-recipient:}") String recipient,
            @Value("${sms-relay.relay-token:}") String relayToken,
            @Value("${sms-relay.operator-token:}") String operatorToken) {
        if (!recipient.matches("\\+216[0-9]{8}")) {
            throw new IllegalArgumentException("SMS_RELAY_TEST_RECIPIENT doit etre au format +216XXXXXXXX");
        }
        if (!relayToken.matches("[A-Za-z0-9_\\-]{32,}") || !operatorToken.matches("[A-Za-z0-9_\\-]{32,}")
                || relayToken.equals(operatorToken)) {
            throw new IllegalArgumentException("Configurer deux jetons SMS distincts et aleatoires");
        }
        this.jdbc = jdbc;
        this.recipient = recipient;
        this.relayToken = relayToken;
        this.operatorToken = operatorToken;
    }

    @PostMapping("/test")
    public ResponseEntity<Void> queue(@RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader("Idempotency-Key") UUID messageId) {
        authenticate(auth, operatorToken);
        jdbc.update("""
                INSERT INTO sms_relay_test_messages (message_id, recipient, state)
                VALUES (?, ?, 'QUEUED') ON CONFLICT (message_id) DO NOTHING
                """, messageId, recipient);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/claim")
    public ResponseEntity<PendingSms> claim(
            @RequestHeader(name = "Authorization", required = false) String auth) {
        authenticate(auth, relayToken);
        var claimed = jdbc.query("""
                UPDATE sms_relay_test_messages SET state='CLAIMED', claimed_at=now()
                WHERE message_id = (
                    SELECT message_id FROM sms_relay_test_messages
                    WHERE state='QUEUED' AND recipient=?
                    ORDER BY created_at, message_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING message_id, recipient
                """, (row, index) -> new PendingSms(row.getObject("message_id", UUID.class),
                        row.getString("recipient"), TEST_MESSAGE), recipient);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(claimed.getFirst());
    }

    @PostMapping("/{messageId}/submitted")
    public ResponseEntity<Void> submitted(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @PathVariable UUID messageId) {
        authenticate(auth, relayToken);
        int updated = jdbc.update("""
                UPDATE sms_relay_test_messages SET state='SUBMITTED',
                    submitted_at=coalesce(submitted_at, now())
                WHERE message_id=? AND state IN ('CLAIMED', 'SUBMITTED') AND recipient=?
                """, messageId, recipient);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void authenticate(String authorization, String token) {
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> relayError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).build();
    }

    public record PendingSms(UUID messageId, String recipient, String message) {}
}
