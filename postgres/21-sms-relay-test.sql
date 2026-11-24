CREATE TABLE IF NOT EXISTS sms_relay_test_messages (
    message_id UUID PRIMARY KEY,
    recipient VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED', 'CLAIMED', 'SUBMITTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_sms_relay_test_queued
    ON sms_relay_test_messages (created_at, message_id) WHERE state = 'QUEUED';

GRANT SELECT, INSERT, UPDATE ON sms_relay_test_messages TO finrisk;
