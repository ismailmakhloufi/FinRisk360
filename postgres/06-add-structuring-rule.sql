-- ============================================================
-- FinRisk360 - Migration 06 : regle RULE-012 (structuration)
--
-- PROBLEME : un fraudeur qui fractionne un montant en N verses
-- sous le seuil de declaration n'etait PAS detecte. Concretement
-- 10 virements de 100 TND en 24h sans RULE-012 notent 25/100 :
--   RULE-001 (montant)  : 100 <= 5000        -> 0
--   RULE-002 (velocite) : 10 > seuil 5        -> +15
--   RULE-006 (ecart)    : 100 ~= moyenne     -> 0
--   RULE-008 (cumul)    : poids 0.10         -> +10
-- => LOW / APPROVED (25 <= 29), l'argent sort. Chaque alerte est
--    LOW et l'analyste voit du bruit, pas un schema.
--
-- SOLUTION : une regle qui detecte le MOTIF plutot que le
-- montant. FEATURE (FeatureEngine.smallTxCount24h / smallTxSum24h)
-- compte, sur la fenetre 24h GLISSANTE ancree sur la transaction,
-- les transactions SORTANTES (PAYMENT/TRANSFER/WITHDRAWAL, pas les
-- DEPOTS) de montant <= risk.structuring.small-amount-max (5000).
-- RULE-012 se declenche au-dela de 3 d'entre elles (zone demi des 4,
-- zone pleine des 5), sous reserve que leur cumul atteigne
-- risk.structuring.min-cumul (500) : sans ce garde-fou, 10
-- micro-virements de 0,01 TND declencheraient la regle a vide.
--
-- POIDS DE 0.30 ET REEQUILIBRAGE v2 : la regle de conception de
-- 02-schema.sql imposait somme(weight) = 1.00 ; RULE-012 porte la somme
-- a 1.35 et le plafond de 100 du ScoringEngine absorbe l'ecart. Le
-- reequilibrage v2 (demo) ajuste 2 conditions/poids existants :
--   - RULE-002 seuil 10 -> 5   : une rafale de 6 tx/24h est deja suspecte
--     (4 virements en 1 minute ne depassait pas l'ancien seuil) ;
--   - RULE-008 poids 0.05 -> 0.10 : le cumul 24h etait sous-pese.
-- A 0.30, RULE-012 atteint a elle seule la zone d'action (30 -> MEDIUM) :
-- c'est le seul cas ou un signal unique doit pouvoir declarer une operation,
-- car la structuration est un predicate PRIMAIRE de l'AML, pas un signal
-- contextuel. A 0.20, 10 virements de 100 TND noteraient encore 25/100 (LOW).
--
-- ddl-auto = validate : rien a creer, uniquement une insertion.
-- ON CONFLICT : 02-schema.sql insert deja RULE-012 pour une base fraiche —
-- cette migration ne s'applique alors qu'aux bases initiees avant le merge.
-- A appliquer avec : psql -v ON_ERROR_STOP=1
--   (finrisk, finrisk_demo, finrisk_test)
-- ============================================================

INSERT INTO risk_rules
    (rule_code, rule_name, risk_category, comparison_operator, threshold_value, weight, severity, enabled)
VALUES
    ('RULE-012', 'Fractionnement sous le seuil de declaration', 'HISTORY', '>', 3.00, 0.30, 'HIGH', TRUE)
ON CONFLICT (rule_code) DO UPDATE
SET threshold_value = EXCLUDED.threshold_value,
    weight          = EXCLUDED.weight;
