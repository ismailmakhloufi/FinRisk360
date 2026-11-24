-- Refuse les doublons plutot que d'effacer des decisions existantes.
-- Si des doublons existent, les examiner et les resoudre avant migration.
CREATE UNIQUE INDEX IF NOT EXISTS uq_night_agent_decision_transaction
    ON night_agent_decisions (transaction_id);
