-- ============================================================================
-- 11-add-transaction-indexes.sql — index de performance sur transactions
-- ============================================================================
-- Diagnostic : la table la plus volumineuse du projet (flux de transactions)
-- n'avait AUCUN index. Chaque page de la console, chaque historique client,
-- chaque scan IA = full scan + tri complet -> console lente sur volume.
--
--   idx_tx_account      : filtre "compte" (page transactions, by-account,
--                         sous-requete beneficiaire du chat SQL)
--   idx_tx_account_date : historique 90 j par compte (analyses IA)
--   idx_tx_date         : filtre "since" global + tri chronologique
--   idx_tx_status       : filtre par statut (PENDING/COMPLETED...)
--
-- A appliquer sur la base finrisk :
--   docker exec -i finrisk-postgres psql -U postgres -d finrisk -f -
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tx_account      ON transactions (account_id);
CREATE INDEX IF NOT EXISTS idx_tx_account_date ON transactions (account_id, transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_tx_date         ON transactions (transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_tx_status       ON transactions (status);

ANALYZE transactions;
