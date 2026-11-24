-- ============================================================
-- FinRisk360 - Migration 04 : statut CANCELLED (validation analyste)
--
-- Le flux Bank Simulator : toute transaction envoyee est evaluee puis
-- soumise a l'analyste, qui la VALIDE (COMPLETED) ou l'ANNULE
-- (CANCELLED — nouveau statut terminal, distinct de FAILED qui reste
-- un echec technique).
--
-- ddl-auto = validate : Hibernate ne lit pas les CHECK, mais PostgreSQL
-- les applique — il faut donc alterer ck_tx_status sur les 3 bases.
-- A appliquer avec : psql -v ON_ERROR_STOP=1  (finrisk, finrisk_demo,
-- finrisk_test)
-- ============================================================

ALTER TABLE transactions DROP CONSTRAINT ck_tx_status;
ALTER TABLE transactions ADD CONSTRAINT ck_tx_status CHECK (status IN
    ('PENDING', 'COMPLETED', 'FAILED', 'HELD', 'CANCELLED'));
