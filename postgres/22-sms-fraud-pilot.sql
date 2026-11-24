BEGIN;

CREATE TABLE IF NOT EXISTS sms_fraud_pilot_reviews (
    transaction_id BIGINT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    ai_verdict VARCHAR(32) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE sms_relay_test_messages ADD COLUMN IF NOT EXISTS fraud_transaction_id BIGINT;
ALTER TABLE sms_relay_test_messages ADD COLUMN IF NOT EXISTS wake_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE sms_relay_test_messages ADD COLUMN IF NOT EXISTS wake_retry_at TIMESTAMPTZ;
ALTER TABLE sms_relay_test_messages ADD COLUMN IF NOT EXISTS wake_sent_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS idx_sms_pilot_transaction
    ON sms_relay_test_messages (fraud_transaction_id);

GRANT SELECT, INSERT ON sms_fraud_pilot_reviews TO finrisk;
COMMIT;
