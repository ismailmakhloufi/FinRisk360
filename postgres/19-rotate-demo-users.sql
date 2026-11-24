-- Run as a database administrator in each operational database.
-- Keep fixture credentials available exclusively in finrisk_test.
SELECT current_database() <> 'finrisk_test' AS operational
\gset
\if :operational
\getenv bootstrap_password FINRISK_BOOTSTRAP_ADMIN_PASSWORD
\if :{?bootstrap_password}
SELECT char_length(:'bootstrap_password') >= 16
       AND octet_length(:'bootstrap_password') <= 72 AS password_valid
\gset
\if :password_valid
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE public.users
SET password_hash = crypt(:'bootstrap_password', gen_salt('bf', 12))
WHERE username = 'admin' AND password_hash = '$2a$10$UO4kDGIRn.5toxhaak8pVe/dYF9vgU8H3ysRebbmYKrY34EURPc4q';

UPDATE public.users
SET is_active = false
WHERE username = 'analyste' AND password_hash = '$2a$10$uxdRSRRP0eS57NvTSt6dZuWAVYGdzAY7.Yz4raJbFOW8FrOp87sEe';
COMMIT;
\else
\echo Le mot de passe admin doit contenir entre 16 caracteres et 72 octets
SELECT 1 / 0;
\endif
\else
\echo FINRISK_BOOTSTRAP_ADMIN_PASSWORD must be set
SELECT 1 / 0;
\endif
\endif
