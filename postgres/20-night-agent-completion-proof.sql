ALTER TABLE night_agent_validation_intents
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_night_intents_prepared
    ON night_agent_validation_intents (created_at, transaction_id)
    WHERE state = 'PREPARED';
