-- Identifiants de mouvements : le marqueur et le solde sont engages dans
-- la meme transaction d'account-service. Ne pas supprimer ces marqueurs lors
-- de la purge d'un compte : une ancienne requete peut toujours etre rejouee.
CREATE TABLE IF NOT EXISTS balance_operation_keys (
    operation_id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount > 0),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON balance_operation_keys TO finrisk;
