-- ============================================================
-- FinRisk360 - Seed de DEMONSTRATION (jalon M7)
-- Base cible : finrisk_demo UNIQUEMENT (jamais finrisk / finrisk_test)
--
-- Principe : aucune donnee pre-existante n'est modifiee, aucun id force
-- (BIGSERIAL auto) donc aucun setval requis. Les nouveaux clients sont
-- identifiables par leur email @finrisk-demo.tn.
--
-- Contenu :
--   12 clients  -> 18 comptes -> 6 marchands -> ~190 transactions
--   + risk_profiles pour que risk-service ait une baseline des l'arrivee
--
-- Le volume est etale sur 90 jours (hors dernières 24h) puis on ajoute :
--   *  6 gros virements  (8k-45k TND, 3 comptes, meme IP/device/beneficiaire)
--   * 16 transactions en rafale sur 5h  (CLIENT ri=3 -> velocity 24h = 16 > 5)
--   * 18 transactions a l'etranger       (usual_country = TN -> RULE-003)
--   * 12 transactions de nuit 01h-03h UTC (02h-04h locale -> RULE-004)
--
-- Dates relatives (NOW() - interval) : le seed reste pertinent des mois
-- plus tard. A appliquer avec : psql -v ON_ERROR_STOP=1
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- 1. CLIENTS (12) - types conformes au CHECK : RETAIL/PREMIUM/SME/CORPORATE
-- ------------------------------------------------------------
INSERT INTO customers
    (first_name, last_name, email, phone, date_of_birth, country, city,
     customer_type, registration_date, status)
VALUES
    ('Sami',   'Trabelsi',  'sami.trabelsi@finrisk-demo.tn',   '+216 22 145 780', '1985-03-14', 'TN', 'Tunis',    'SME',        NOW() - interval '26 months', 'ACTIVE'),
    ('Nadia',  'Gharbi',    'nadia.gharbi@finrisk-demo.tn',    '+216 98 332 114', '1992-11-02', 'TN', 'Sousse',   'PREMIUM',    NOW() - interval '19 months', 'ACTIVE'),
    ('Karim',  'Ben Salah', 'karim.bensalah@finrisk-demo.tn',  '+216 55 908 471', '1978-06-25', 'TN', 'Sfax',     'CORPORATE',  NOW() - interval '34 months', 'ACTIVE'),
    ('Leila',  'Mansouri',  'leila.mansouri@finrisk-demo.tn',  '+216 21 477 063', '1996-01-19', 'TN', 'Tunis',    'RETAIL',     NOW() - interval '7 months',  'ACTIVE'),
    ('Youssef', 'Hammami',  'youssef.hammami@finrisk-demo.tn', '+216 27 620 984', '1989-09-30', 'TN', 'Bizerte',  'RETAIL',     NOW() - interval '14 months', 'ACTIVE'),
    ('Amira',  'Chatti',    'amira.chatti@finrisk-demo.tn',    '+216 54 213 776', '1994-04-08', 'TN', 'Sousse',   'PREMIUM',    NOW() - interval '11 months', 'ACTIVE'),
    ('Mehdi',  'Jlassi',    'mehdi.jlassi@finrisk-demo.tn',    '+216 29 845 201', '1983-12-12', 'TN', 'Sfax',     'SME',        NOW() - interval '30 months', 'ACTIVE'),
    ('Rania',  'Farhat',    'rania.farhat@finrisk-demo.tn',    '+216 92 501 339', '1999-07-21', 'TN', 'Tunis',    'RETAIL',     NOW() - interval '3 months',  'ACTIVE'),
    ('Anis',   'Bouzid',    'anis.bouzid@finrisk-demo.tn',     '+216 26 384 615', '1975-02-05', 'TN', 'Kairouan', 'CORPORATE',  NOW() - interval '40 months', 'SUSPENDED'),
    ('Sarra',  'Ouali',     'sarra.ouali@finrisk-demo.tn',     '+216 51 726 908', '1997-10-17', 'TN', 'Monastir', 'RETAIL',     NOW() - interval '5 months',  'ACTIVE'),
    ('Fedi',   'Zouari',    'fedi.zouari@finrisk-demo.tn',     '+216 23 159 442', '1987-05-27', 'TN', 'Tunis',    'PREMIUM',    NOW() - interval '22 months', 'ACTIVE'),
    ('Ines',   'Khelifi',   'ines.khelifi@finrisk-demo.tn',    '+216 97 630 285', '1993-08-11', 'TN', 'Gabes',    'RETAIL',     NOW() - interval '9 months',  'ACTIVE');

-- ------------------------------------------------------------
-- 2. COMPTES (18) : 12 principaux (un par client) + 6 secondaires
--    Statuts melanges pour montrer les badges (2 FROZEN, 1 CLOSED).
-- ------------------------------------------------------------
WITH demo_clients AS (
    SELECT customer_id,
           row_number() OVER (ORDER BY customer_id) AS ri
    FROM customers
    WHERE email LIKE '%@finrisk-demo.tn'
)
INSERT INTO accounts
    (customer_id, account_number, account_type, currency, balance, opening_date, status)
SELECT d.customer_id,
       'TN9' || lpad((100000000 + d.ri)::text, 11, '0'),
       CASE d.ri % 3 WHEN 0 THEN 'CHECKING' WHEN 1 THEN 'SAVINGS' ELSE 'CARD' END,
       'TND',
       round((600 + d.ri * 1377.25)::numeric, 2),
       CURRENT_DATE - (d.ri * 11)::int,
       CASE WHEN d.ri IN (6, 10) THEN 'FROZEN'
            WHEN d.ri = 8 THEN 'CLOSED'
            ELSE 'ACTIVE' END
FROM demo_clients d;

WITH demo_clients AS (
    SELECT customer_id,
           row_number() OVER (ORDER BY customer_id) AS ri
    FROM customers
    WHERE email LIKE '%@finrisk-demo.tn'
)
INSERT INTO accounts
    (customer_id, account_number, account_type, currency, balance, opening_date, status)
SELECT d.customer_id,
       'TN8' || lpad((500000000 + d.ri)::text, 11, '0'),
       CASE d.ri % 2 WHEN 0 THEN 'SAVINGS' ELSE 'CHECKING' END,
       'TND',
       round((15000 + d.ri * 2410.60)::numeric, 2),
       CURRENT_DATE - (40 + d.ri)::int,
       'ACTIVE'
FROM demo_clients d
WHERE d.ri <= 6;

-- ------------------------------------------------------------
-- 3. MARCHANDS (5) : 3 tunisiens + 2 etrangers pour RULE-003
-- ------------------------------------------------------------
INSERT INTO merchants (merchant_name, merchant_category, country, city, status)
VALUES
    ('TECHSTORE.TN',    'E_COMMERCE', 'TN', 'Tunis',  'ACTIVE'),
    ('STATION VOYAGE',  'TRAVEL',     'TN', 'Sousse', 'ACTIVE'),
    ('GRO MARCHE PLUS', 'GROCERY',    'TN', 'Sfax',   'ACTIVE'),
    ('CRYPTO EU GmbH',  'CRYPTO',     'DE', 'Berlin', 'ACTIVE'),
    ('LUXE PARIS SAS',  'RETAIL',     'FR', 'Paris',  'ACTIVE');

-- ------------------------------------------------------------
-- 4. RISK PROFILES (12) : baseline immobilisee pour l'IA/moteur.
--    Profils volontairement sobres : les regles doivent rester
--    declenchables par les transactions LIVE de la demo.
-- ------------------------------------------------------------
INSERT INTO risk_profiles
    (customer_id, avg_transaction_amount, median_transaction_amount,
     max_transaction_amount, p95_amount, p99_amount, transactions_per_day,
     usual_country, usual_transaction_hour, usual_merchant_count,
     stats_window_days, profile_risk_level, last_updated)
SELECT c.customer_id,
       480.00, 310.00, 4800.00, 1950.00, 3600.00, 2.40,
       'TN', 14, 6, 90,
       CASE WHEN c.customer_id % 5 = 0 THEN 'MEDIUM' ELSE 'LOW' END,
       NOW()
FROM customers c
WHERE c.email LIKE '%@finrisk-demo.tn';

-- ------------------------------------------------------------
-- 5. VOLUME HISTORIQUE (~144 tx sur 89 jours, HORS dernières 24h)
--    18 comptes x 8 transactions. Payements/TND/habituelles.
-- ------------------------------------------------------------
INSERT INTO transactions
    (account_id, merchant_id, amount, currency, transaction_type, country,
     transaction_date, channel, ip_address, device_id, beneficiary_name, status)
SELECT a.account_id,
       CASE WHEN floor(random() * 10) < 6
            THEN (SELECT merchant_id FROM merchants ORDER BY merchant_id LIMIT 1 OFFSET (g % 3))
       END,
       round((45 + random() * 435)::numeric, 2),
       'TND',
       CASE WHEN floor(random() * 10) < 6 THEN 'PAYMENT'
            WHEN floor(random() * 10) < 8 THEN 'TRANSFER'
            WHEN floor(random() * 10) < 9 THEN 'WITHDRAWAL'
            ELSE 'DEPOSIT' END,
       'TN',
       NOW() - interval '1 day' - (random() * interval '88 days') - (random() * interval '20 hours'),
       (ARRAY['MOBILE', 'WEB', 'ATM', 'POS'])[1 + floor(random() * 4)],
       '196.201.' || (1 + floor(random() * 250))::text || '.' || (1 + floor(random() * 250))::text,
       'DEV-' || lpad(((a.account_id * 7 + g) % 9999)::text, 4, '0'),
       CASE WHEN g % 5 = 0 THEN 'BOUTIQUE EL MOUWATIN' END,
       'COMPLETED'
FROM accounts a
JOIN customers c ON c.customer_id = a.customer_id
               AND c.email LIKE '%@finrisk-demo.tn'
CROSS JOIN generate_series(1, 8) g;

-- ------------------------------------------------------------
-- 6. NOCTURNES (12 tx a 02h-04h locale = 01h-03h UTC) -> RULE-004
-- ------------------------------------------------------------
INSERT INTO transactions
    (account_id, merchant_id, amount, currency, transaction_type, country,
     transaction_date, channel, ip_address, device_id, beneficiary_name, status)
SELECT a.account_id,
       (SELECT merchant_id FROM merchants ORDER BY merchant_id LIMIT 1 OFFSET (g % 3)),
       round((60 + random() * 400)::numeric, 2),
       'TND',
       'PAYMENT',
       'TN',
       date_trunc('day', NOW() - (g * interval '4 days')) + interval '30 minutes' + (g * interval '13 minutes'),
       'ATM',
       '105.158.' || (1 + g % 200)::text || '.7',
       'DEV-' || lpad(((a.account_id * 3 + g) % 9999)::text, 4, '0'),
       NULL,
       'COMPLETED'
FROM (SELECT account_id FROM accounts a
      JOIN customers c ON c.customer_id = a.customer_id
                     AND c.email LIKE '%@finrisk-demo.tn'
      ORDER BY account_id
      OFFSET 2 LIMIT 6) a,
     generate_series(1, 2) g;

-- ------------------------------------------------------------
-- 7. ETRANGER (18 tx DE/FR/AE) : usual_country reste TN -> RULE-003
-- ------------------------------------------------------------
INSERT INTO transactions
    (account_id, merchant_id, amount, currency, transaction_type, country,
     transaction_date, channel, ip_address, device_id, beneficiary_name, status)
SELECT a.account_id,
       (SELECT merchant_id FROM merchants ORDER BY merchant_id LIMIT 1 OFFSET ((g + 3) % 5)),
       round((120 + random() * 900)::numeric, 2),
       'TND',
       'PAYMENT',
       (ARRAY['DE', 'FR', 'AE'])[1 + (g % 3)],
       NOW() - interval '2 days' - (random() * interval '70 days'),
       'WEB',
       '91.203.' || (1 + g % 200)::text || '.14',
       'DEV-TRAVEL-' || lpad(g::text, 3, '0'),
       CASE WHEN g % 2 = 0 THEN 'PAIEMENT INTERNATIONAL' END,
       'COMPLETED'
FROM (SELECT account_id FROM accounts a
      JOIN customers c ON c.customer_id = a.customer_id
                     AND c.email LIKE '%@finrisk-demo.tn'
      ORDER BY account_id
      OFFSET 6 LIMIT 6) a,
     generate_series(1, 3) g;

-- ------------------------------------------------------------
-- 8. GROS VIREMENTS PASSES (6 x 8k-45k, 3 comptes) :
--    meme IP 41.104.22.9, meme device, meme beneficiaire
--    -> reseau partage exploite par les insights IA.
-- ------------------------------------------------------------
INSERT INTO transactions
    (account_id, merchant_id, amount, currency, transaction_type, country,
     transaction_date, channel, ip_address, device_id, beneficiary_name, status)
SELECT a.account_id,
       NULL,
       round((8000 + random() * 37000)::numeric, 2),
       'TND',
       'TRANSFER',
       'TN',
       NOW() - (5 + g * 9) * interval '1 day' - (g * interval '50 minutes'),
       'WEB',
       '41.104.22.9',
       'DEV-SHARED-01',
       'SOCIETE DEMO SARL',
       'COMPLETED'
FROM (SELECT account_id FROM accounts a
      JOIN customers c ON c.customer_id = a.customer_id
                     AND c.email LIKE '%@finrisk-demo.tn'
      ORDER BY account_id
      LIMIT 3) a,
     generate_series(1, 2) g;

-- ------------------------------------------------------------
-- 9. RAFALE RECENTE (16 tx en 5h sur le client ri=3 = Karim Ben Salah,
--    ses 2 comptes) -> txCount24h = 16 > 10*1.5 -> RULE-002 factor 1.0
--    C'est LE client a observer sur le Dashboard la veille de la demo.
-- ------------------------------------------------------------
INSERT INTO transactions
    (account_id, merchant_id, amount, currency, transaction_type, country,
     transaction_date, channel, ip_address, device_id, beneficiary_name, status)
SELECT a.account_id,
       (SELECT merchant_id FROM merchants ORDER BY merchant_id LIMIT 1 OFFSET (g % 3)),
       round((900 + g * 145 + random() * 300)::numeric, 2),
       'TND',
       'PAYMENT',
       'TN',
       NOW() - (g * interval '20 minutes'),
       'MOBILE',
       '41.104.22.63',
       'DEV-RAFALE-01',
       'RECHARGE MONDIAL PAY',
       'COMPLETED'
FROM (SELECT account_id FROM accounts a
      JOIN customers c ON c.customer_id = a.customer_id
                     AND c.email LIKE '%@finrisk-demo.tn'
      ORDER BY c.customer_id, a.account_id
      OFFSET 4 LIMIT 2) a,
     generate_series(1, 8) g;

COMMIT;

-- ------------------------------------------------------------
-- 10. CONTROLES (affiches par psql, sans ON_ERROR_STOP)
-- ------------------------------------------------------------
SELECT 'customers'   AS table_name, count(*) AS nb FROM customers
  WHERE email LIKE '%@finrisk-demo.tn'
UNION ALL SELECT 'accounts',   count(*) FROM accounts a JOIN customers c USING (customer_id)
  WHERE c.email LIKE '%@finrisk-demo.tn'
UNION ALL SELECT 'merchants',  count(*) FROM merchants
UNION ALL SELECT 'risk_profiles', count(*) FROM risk_profiles
UNION ALL SELECT 'transactions', count(*) FROM transactions t
  JOIN accounts a USING (account_id) JOIN customers c USING (customer_id)
  WHERE c.email LIKE '%@finrisk-demo.tn'
UNION ALL SELECT '  dont >5000 TND', count(*) FROM transactions t
  JOIN accounts a USING (account_id) JOIN customers c USING (customer_id)
  WHERE c.email LIKE '%@finrisk-demo.tn' AND t.amount > 5000
UNION ALL SELECT '  dont en 24h (Karim)', count(*) FROM transactions t
  JOIN accounts a USING (account_id) JOIN customers c USING (customer_id)
  WHERE c.email = 'karim.bensalah@finrisk-demo.tn'
    AND t.transaction_date >= NOW() - interval '24 hours';
