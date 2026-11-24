-- Intention de validation : persistee avant tout appel financier de l'agent.
-- Une intention n'est pas une preuve de validation : elle ne doit jamais
-- etre affichee comme VALIDATED sans preuve issue de transaction-service.
CREATE TABLE IF NOT EXISTS night_agent_validation_intents (
    transaction_id BIGINT PRIMARY KEY,
    intent_id UUID NOT NULL UNIQUE,
    assessment_id BIGINT NOT NULL,
    alert_id BIGINT NOT NULL,
    amount NUMERIC NOT NULL,
    risk_score NUMERIC,
    risk_level VARCHAR(20) NOT NULL,
    engine_decision VARCHAR(30),
    ai_verdict VARCHAR(30) NOT NULL,
    ai_confidence DOUBLE PRECISION,
    agent_reason VARCHAR(500) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('PREPARED', 'FINALIZED', 'RECONCILIATION')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON night_agent_validation_intents TO finrisk;
