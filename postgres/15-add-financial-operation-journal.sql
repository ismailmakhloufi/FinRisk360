-- Journal des etapes financieres : cle stable par tentative et par etape.
-- Il ne doit pas etre purge automatiquement lorsqu'une reponse est perdue.
CREATE TABLE IF NOT EXISTS financial_operation_journal (
    attempt_id UUID NOT NULL,
    step VARCHAR(32) NOT NULL,
    operation_id UUID NOT NULL UNIQUE,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount > 0),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    state VARCHAR(20) NOT NULL CHECK (state IN ('PREPARED', 'CONFIRMED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, step)
);
GRANT SELECT, INSERT, UPDATE ON financial_operation_journal TO finrisk;
-- abort() libere une tentative dont la compensation a ete confirmee.
GRANT DELETE ON financial_operation_journal TO finrisk;
