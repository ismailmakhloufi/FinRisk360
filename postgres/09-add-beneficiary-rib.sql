-- ============================================================
-- 09 : RIB DU BENEFICIAIRE stocke sur la transaction
--
-- Regle metier : un virement n'est cree QUE si le RIB est saisi
-- correctement (20 chiffres + cle modulo 97). Le RIB est la SEULE
-- identite du beneficiaire — jamais le nom (se tape avec des fautes).
--
--   - RIB trouve en base  -> beneficiaire INTERNE : beneficiary_account_id
--     est resolu par account-service (GET /api/accounts/rib/{rib}) et le
--     compte sera credite a la REGULATION ;
--   - RIB bien forme mais inconnu -> beneficiaire EXTERNE (autre banque) :
--     beneficiary_account_id reste NULL, seul le debit du compte source
--     est applique ;
--   - RIB mal forme (longueur ou cle) -> 400, AUCUNE transaction creee.
--
-- Format canonique stocke : "TN 0109 001 xxxxxxxxxx xx" (banque 4,
-- agence 3, compte 11, cle 2). Ajoute par la migration 09.
-- ============================================================
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS beneficiary_rib VARCHAR(26);

COMMENT ON COLUMN transactions.beneficiary_rib IS
    'RIB TN canonique saisi pour le virement ; NULL = pas de RIB fourni '
    '(paiement marchand, flux de demonstration).';
