-- Un marqueur durable bloque les nouvelles decisions apres un resultat
-- financier incertain. Aucune suppression automatique : reconciliation humaine.
CREATE TABLE IF NOT EXISTS financial_attempt_guard (
    transaction_id BIGINT PRIMARY KEY,
    state VARCHAR(20) NOT NULL CHECK (state IN ('IN_PROGRESS', 'FINISHED')),
    target_status VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON financial_attempt_guard TO finrisk;
