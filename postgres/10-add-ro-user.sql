-- ============================================================================
-- 10-add-ro-user.sql — user LECTURE SEULE pour le chat SQL de l'assistant IA
-- ============================================================================
-- L'ai-assistant-service (chat IA) a besoin de repondre a des questions de
-- type « le compte X a-t-il paye le beneficiaire Y ? ». Plutot que d'ouvrir
-- les API de chaque service, il interroge directement la base finrisk via un
-- user DEDIE et privegié :
--
--   finrisk_ro : droits SELECT uniquement — meme un SQL malveillant qui
--   passerait le garde-fou ne pourrait rien ecrire ni supprimer.
--
-- A appliquer sur la base finrisk (docker finrisk-postgres, superuser
-- postgres) :  docker exec -i finrisk-postgres psql -U postgres -d finrisk -f -
-- ============================================================================

\getenv ai_db_password AI_DB_PASSWORD
BEGIN;
SELECT 'CREATE ROLE finrisk_ro LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finrisk_ro')
\gexec
ALTER ROLE finrisk_ro PASSWORD :'ai_db_password';

GRANT CONNECT ON DATABASE finrisk TO finrisk_ro;
GRANT USAGE ON SCHEMA public TO finrisk_ro;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM finrisk_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM finrisk_ro;

GRANT SELECT ON transactions, accounts, customers, merchants,
    risk_assessments, alerts, alert_groups, cases TO finrisk_ro;
ALTER ROLE finrisk_ro SET statement_timeout = '5s';
ALTER ROLE finrisk_ro SET default_transaction_read_only = on;
COMMIT;
