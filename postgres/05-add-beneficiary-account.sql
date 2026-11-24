-- ============================================================
-- FinRisk360 - Migration 05 : compte beneficiaire + regulation
--
-- AVANT : le simulateur debitait le source ET creditait le
-- destinataire AU MOMENT DE LA CREATION, puis soumettait la
-- transaction a l'analyste. Deux defauts :
--   1. l'argent bougeait AVANT toute decision — la mention
--      "en attente de validation" n'etait qu'affichee ;
--   2. si l'analyste annulait, reverseBalanceForCancel ne
--      remboursait QUE le compte source : le credit du
--      destinataire restait -> l'argent etait cree de rien.
--
-- APRES : aucun solde ne bouge a la creation. La REGULATION
-- (debit du source + credit du beneficiaire interne) est
-- appliquee par transaction-service QUAND L'ANALYSTE VALIDE
-- (PATCH /status?status=COMPLETED). Une annulation ne bouge
-- donc rien a reverser.
--
-- beneficiary_account_id :
--   NULL      -> destinataire EXTERNE (autre banque) : seul le
--                debit du compte source est applique ;
--   NON NULL  -> compte interne (trouve par RIB) : credit aussi.
--
-- ddl-auto = validate : Hibernate verifie la presence des colonnes.
-- A appliquer avec : psql -v ON_ERROR_STOP=1
--   (finrisk, finrisk_demo, finrisk_test)
-- ============================================================

ALTER TABLE transactions
    ADD COLUMN beneficiary_account_id BIGINT;

ALTER TABLE transactions
    ADD CONSTRAINT fk_tx_beneficiary
    FOREIGN KEY (beneficiary_account_id)
    REFERENCES accounts (account_id) ON DELETE SET NULL;

CREATE INDEX idx_tx_beneficiary ON transactions (beneficiary_account_id);
