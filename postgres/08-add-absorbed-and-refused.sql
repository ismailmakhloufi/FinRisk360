-- ============================================================
-- FinRisk360 - Migration 08 : absorption des alertes + refus
--               de groupe avec remboursement
--
-- PARTIE A — ABSORPTION (modele « 10 transactions -> 1 alerte »)
-- PROBLEME : 10 virements fractionnes ouvrent 10 alertes ; le
-- GROUPE agrège le pattern, mais la file de l'analyste affiche
-- quand meme les 10 alertes individuelles : le bruit persiste.
--
-- SOLUTION : la 1re alerte du groupe reste OPEN (point d'entree),
-- toutes les suivantes passent en statut ABSORBED (absorbee par le
-- groupe). Elles restent en base (trace regulamentaire, membres
-- consultables via /api/alert-groups/{id}/alerts) mais sont
-- EXCLUES de la liste principale (GET /api/alerts sans filtre).
--
-- PARTIE B — REFUS DE GROUPE (decision + annulation + remboursement)
-- L'analyste tranche sur le PATTERN : ACCEPT (legitime) ou REFUSE
-- (fraude confirmee). REFUSE = annuler + rembourser TOUTES les
-- transactions du groupe -> le groupe passe en statut REFUSED
-- (nouveau). Les alertes membres sont cloturees en masse cote
-- application (hors graphe AlertStatus : c'est une decision de
-- groupe, pas une transition individuelle).
--
-- ddl-auto = validate : tout est cree ci-dessous.
-- A appliquer avec : psql -v ON_ERROR_STOP=1
--   (finrisk, finrisk_demo, finrisk_test)
-- ============================================================

-- 1. ABSORBED : alerte absorbee par son groupe (etat terminal masque).
ALTER TABLE alerts DROP CONSTRAINT ck_alerts_status;
ALTER TABLE alerts ADD CONSTRAINT ck_alerts_status CHECK (status IN
    ('OPEN','ACKNOWLEDGED','IN_PROGRESS','RESOLVED','FALSE_POSITIVE','ABSORBED'));

-- 2. REFUSED : groupe refuse par l'analyste (transactions annulees
--    et remboursees). Etat terminal.
ALTER TABLE alert_groups DROP CONSTRAINT ck_ag_status;
ALTER TABLE alert_groups ADD CONSTRAINT ck_ag_status CHECK (status IN
    ('OPEN','RESOLVED','FALSE_POSITIVE','REFUSED'));
