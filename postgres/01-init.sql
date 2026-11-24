-- ============================================================
-- Premier demarrage du conteneur uniquement (volume vierge)
-- ============================================================

-- Role applicatif utilise par les microservices
\getenv finrisk_db_password FINRISK_DB_PASSWORD
CREATE ROLE finrisk LOGIN PASSWORD :'finrisk_db_password';

-- Base operationnelle (OLTP)
GRANT ALL PRIVILEGES ON DATABASE finrisk TO finrisk;
\c finrisk
GRANT ALL ON SCHEMA public TO finrisk;
ALTER DATABASE finrisk OWNER TO finrisk;

-- Les migrations 04 a 09 sont deja integrees au schema de reference.
\ir 02-schema.sql
\ir 10-add-ro-user.sql
\ir 11-add-transaction-indexes.sql
\ir 12-add-night-agent.sql
\ir 13-add-balance-operation-keys.sql
\ir 14-add-financial-attempt-guard.sql
\ir 15-add-financial-operation-journal.sql
\ir 16-night-agent-decision-unique.sql
\ir 17-night-agent-validation-intents.sql
\ir 18-bank-simulator-role.sql
\ir 20-night-agent-completion-proof.sql
\ir 21-sms-relay-test.sql
\ir 22-sms-fraud-pilot.sql

-- Cloner le schema complet avant les rotations et les donnees de demonstration.
\connect postgres
CREATE DATABASE finrisk_test WITH TEMPLATE finrisk OWNER finrisk;
CREATE DATABASE finrisk_demo WITH TEMPLATE finrisk OWNER finrisk;

\connect finrisk
\ir 19-rotate-demo-users.sql

\connect finrisk_demo
\ir 19-rotate-demo-users.sql
\ir 03-seed-demo.sql
