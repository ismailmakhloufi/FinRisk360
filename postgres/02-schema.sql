-- ============================================================
-- FinRisk360 - Schema relationnel PostgreSQL 17
-- Base : finrisk (OLTP)
-- Source de verite : ce fichier (Hibernate en mode "validate")
-- ============================================================

-- ------------------------------------------------------------
-- 1. IDENTITE ET COMPTES
-- ------------------------------------------------------------

CREATE TABLE customers (
    customer_id        BIGSERIAL      PRIMARY KEY,
    first_name         VARCHAR(50)    NOT NULL,
    last_name          VARCHAR(50)    NOT NULL,
    email              VARCHAR(100),
    phone              VARCHAR(20),
    date_of_birth      DATE,
    country            CHAR(2),
    city               VARCHAR(50),
    customer_type      VARCHAR(20)    NOT NULL DEFAULT 'RETAIL',
    registration_date  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    status             VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_customers_email      UNIQUE (email),
    CONSTRAINT ck_customers_type       CHECK (customer_type IN ('RETAIL','PREMIUM','SME','CORPORATE')),
    CONSTRAINT ck_customers_status     CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED'))
);

CREATE TABLE users (
    user_id        BIGSERIAL       PRIMARY KEY,
    username       VARCHAR(50)     NOT NULL,
    email          VARCHAR(100),
    password_hash  VARCHAR(255)    NOT NULL,
    role           VARCHAR(20)     NOT NULL DEFAULT 'ANALYST',
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username   UNIQUE (username),
    CONSTRAINT uq_users_email      UNIQUE (email),
    CONSTRAINT ck_users_role       CHECK (role IN ('ADMIN','ANALYST','MANAGER','BANK_SIMULATOR'))
);

CREATE TABLE accounts (
    account_id      BIGSERIAL     PRIMARY KEY,
    customer_id     BIGINT        NOT NULL,
    account_number  VARCHAR(20)   NOT NULL,
    account_type    VARCHAR(20)   NOT NULL DEFAULT 'CHECKING',
    currency        CHAR(3)       NOT NULL DEFAULT 'TND',
    balance         DECIMAL(15,2) NOT NULL DEFAULT 0,
    opening_date    DATE          NOT NULL DEFAULT CURRENT_DATE,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_accounts_number   UNIQUE (account_number),
    CONSTRAINT ck_accounts_type     CHECK (account_type IN ('CHECKING','SAVINGS','CARD')),
    CONSTRAINT ck_accounts_status   CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 2. TRANSACTIONNEL
-- ------------------------------------------------------------

CREATE TABLE merchants (
    merchant_id        BIGSERIAL     PRIMARY KEY,
    merchant_name      VARCHAR(100)  NOT NULL,
    merchant_category  VARCHAR(50),
    country            CHAR(2),
    city               VARCHAR(50),
    status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchants_status CHECK (status IN ('ACTIVE','INACTIVE','BLOCKED'))
);

CREATE TABLE transactions (
    transaction_id     BIGSERIAL      PRIMARY KEY,
    account_id         BIGINT         NOT NULL,
    merchant_id        BIGINT,
    amount             DECIMAL(15,2)  NOT NULL,
    currency           CHAR(3)        NOT NULL DEFAULT 'TND',
    transaction_type   VARCHAR(20)    NOT NULL,
    country            CHAR(2)        NOT NULL,
    transaction_date   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    channel            VARCHAR(20),
    ip_address         VARCHAR(50),
    device_id          VARCHAR(50),
    beneficiary_name   VARCHAR(100),
    -- Compte du destinataire interne a crediter lors de la REGULATION.
    -- Null = destinataire externe (autre banque) : seul le debit du compte
    -- source est applique. Ajoute par la migration 05.
    beneficiary_account_id BIGINT,
    -- RIB TN canonique du beneficiaire, valide (20 chiffres + cle mod 97)
    -- et resolu cote API a la creation. Null = pas de RIB fourni.
    -- Ajoute par la migration 09.
    beneficiary_rib        VARCHAR(26),
    status             VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_tx_amount        CHECK (amount > 0),
    CONSTRAINT ck_tx_type          CHECK (transaction_type IN ('PAYMENT','TRANSFER','WITHDRAWAL','DEPOSIT')),
    -- 'CANCELLED' ajoute par la migration 04 (validation analyste).
    CONSTRAINT ck_tx_status        CHECK (status IN ('PENDING','COMPLETED','FAILED','HELD','CANCELLED')),
    CONSTRAINT ck_tx_channel       CHECK (channel IS NULL OR channel IN ('MOBILE','WEB','ATM','POS','API')),
    CONSTRAINT fk_tx_account       FOREIGN KEY (account_id)
        REFERENCES accounts (account_id) ON DELETE RESTRICT,
    CONSTRAINT fk_tx_beneficiary   FOREIGN KEY (beneficiary_account_id)
        REFERENCES accounts (account_id) ON DELETE SET NULL,
    CONSTRAINT fk_tx_merchant      FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 3. RISQUE
-- ------------------------------------------------------------

CREATE TABLE risk_profiles (
    profile_id                BIGSERIAL      PRIMARY KEY,
    customer_id               BIGINT         NOT NULL,
    avg_transaction_amount    DECIMAL(15,2),
    median_transaction_amount DECIMAL(15,2),
    max_transaction_amount    DECIMAL(15,2),
    p95_amount                DECIMAL(15,2),
    p99_amount                DECIMAL(15,2),
    transactions_per_day      DECIMAL(5,2),
    feature_snapshot          JSONB,
    usual_country             CHAR(2),
    usual_transaction_hour    SMALLINT,
    usual_merchant_count      INTEGER,
    stats_window_days         INTEGER        NOT NULL DEFAULT 90,
    profile_risk_level        VARCHAR(20)    DEFAULT 'LOW',
    last_updated              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_risk_profiles_customer UNIQUE (customer_id),
    CONSTRAINT ck_rp_hour     CHECK (usual_transaction_hour IS NULL
                                     OR (usual_transaction_hour >= 0 AND usual_transaction_hour <= 23)),
    CONSTRAINT ck_rp_level    CHECK (profile_risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT fk_rp_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE CASCADE
);

CREATE TABLE risk_rules (
    rule_id              BIGSERIAL     PRIMARY KEY,
    rule_code            VARCHAR(20)   NOT NULL,
    rule_name            VARCHAR(100)  NOT NULL,
    risk_category        VARCHAR(20)   NOT NULL,
    comparison_operator  VARCHAR(10),
    threshold_value      DECIMAL(10,2),
    weight               DECIMAL(4,2)  NOT NULL DEFAULT 0,
    severity             VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    enabled              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_risk_rules_code   UNIQUE (rule_code),
    CONSTRAINT ck_rr_category  CHECK (risk_category IN ('AMOUNT','VELOCITY','GEOGRAPHIC','TIME','BEHAVIOR','HISTORY')),
    CONSTRAINT ck_rr_operator  CHECK (comparison_operator IS NULL
                                      OR comparison_operator IN ('>','<','=','>=','<=','BETWEEN')),
    CONSTRAINT ck_rr_severity  CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

CREATE TABLE risk_assessments (
    assessment_id   BIGSERIAL      PRIMARY KEY,
    transaction_id  BIGINT         NOT NULL,
    risk_score      DECIMAL(5,2)   NOT NULL,
    risk_level      VARCHAR(20)    NOT NULL,
    decision        VARCHAR(20)    NOT NULL,
    assessed_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    engine_version  VARCHAR(20),
    CONSTRAINT ck_ra_score  CHECK (risk_score >= 0 AND risk_score <= 100),
    CONSTRAINT ck_ra_level  CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_ra_decision CHECK (decision IN ('APPROVED','MONITORING','PENDING_REVIEW','HOLD')),
    CONSTRAINT fk_ra_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id) ON DELETE CASCADE
);

CREATE TABLE risk_factors (
    factor_id     BIGSERIAL     PRIMARY KEY,
    assessment_id BIGINT        NOT NULL,
    rule_id       BIGINT,
    factor_type   VARCHAR(20)   NOT NULL,
    factor_score  DECIMAL(5,2),
    weight        DECIMAL(4,2),
    reason        TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_rf_type CHECK (factor_type IN ('AMOUNT','VELOCITY','GEOGRAPHIC','TIME','BEHAVIOR','HISTORY')),
    CONSTRAINT fk_rf_assessment FOREIGN KEY (assessment_id)
        REFERENCES risk_assessments (assessment_id) ON DELETE CASCADE,
    CONSTRAINT fk_rf_rule FOREIGN KEY (rule_id)
        REFERENCES risk_rules (rule_id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 4. INVESTIGATION ET GOUVERNANCE
-- ------------------------------------------------------------

CREATE TABLE alerts (
    alert_id       BIGSERIAL     PRIMARY KEY,
    assessment_id  BIGINT        NOT NULL,
    alert_code     VARCHAR(30)   NOT NULL,
    alert_type     VARCHAR(20)   NOT NULL,
    severity       VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    assigned_to    BIGINT,
    group_id       BIGINT,
    amount         DECIMAL(15,2),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,
    CONSTRAINT uq_alerts_code      UNIQUE (alert_code),
    CONSTRAINT ck_alerts_type      CHECK (alert_type IN ('HIGH_AMOUNT','VELOCITY','GEOGRAPHIC','BEHAVIOR','DUPLICATE','OTHER')),
    CONSTRAINT ck_alerts_severity  CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_alerts_status    CHECK (status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS','RESOLVED','FALSE_POSITIVE','ABSORBED')),
    CONSTRAINT fk_alerts_assessment FOREIGN KEY (assessment_id)
        REFERENCES risk_assessments (assessment_id) ON DELETE CASCADE,
    CONSTRAINT fk_alerts_assignee FOREIGN KEY (assigned_to)
        REFERENCES users (user_id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 4bis. AGREGATION D'ALERTES (structuration / signaux repetes)
--
-- Un meme compte qui produit 10 alertes LOW en 24h n'est PAS 10 fois
-- anodin : c'est le motif meme que RULE-012 cherche a detecter. Chaque
-- alerte conserve sa severite INDIVIDUELLE (elle reflete SON evaluation),
-- mais le GROUPE porte une severite AGREEGEE qui monte avec le nombre
-- d'alertes apparentees. L'analyste voit alors un pattern, pas du bruit.
--
-- scope_type/scope_value : le perimetre de regroupement (aujourd'hui
-- ACCOUNT, le compte emetteur). Les autres valeurs sont prevues pour
-- l'extension (BENEFICIARY, CUSTOMER) sans migration.
--
-- window_start + window_hours : fenetre GLISSANTE. Un groupe n'accueille
-- que des alertes creees dans la fenetre ; passe le delai, la prochaine
-- alerte ouvre un nouveau groupe.
-- ------------------------------------------------------------
CREATE TABLE alert_groups (
    group_id        BIGSERIAL     PRIMARY KEY,
    scope_type      VARCHAR(20)   NOT NULL,
    scope_value     VARCHAR(60)   NOT NULL,
    customer_id     BIGINT,
    window_start    TIMESTAMPTZ   NOT NULL,
    window_hours    INT           NOT NULL,
    severity        VARCHAR(20)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    related_count   INT           NOT NULL DEFAULT 0,
    total_amount    DECIMAL(15,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Compteur et cumul sont incrementes par une UPDATE atomique
    -- (AlertGroupRepository.incrementAndEscalate), pas par une
    -- lecture-modification-ecriture : pas de conflit d'optimistic
    -- locking a faire echouer sous rafale.
    version         BIGINT        NOT NULL DEFAULT 0,
    -- UN SEUL groupe ouvert par perimetre : c'est cette contrainte
    -- PARTIELLE qui garantit l'invariant. Elle rend possible
    -- INSERT ... ON CONFLICT DO NOTHING, qui n'abort PAS la
    -- transaction PostgreSQL quand un concurrent a deja cree le
    -- groupe (un INSERT nu le ferait, et tuerait l'alerte avec).
    CONSTRAINT ck_ag_scope   CHECK (scope_type IN ('ACCOUNT','CUSTOMER','BENEFICIARY')),
    CONSTRAINT ck_ag_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_ag_status  CHECK (status IN ('OPEN','RESOLVED','FALSE_POSITIVE','REFUSED'))
);

-- Index unique PARTIEL : un seul groupe ouvert par perimetre. Les groupes
-- clotures (RESOLVED / FALSE_POSITIVE) ne le disputent pas, donc deux
-- episodes espaces dans le temps cohabitent sans se geler.
CREATE UNIQUE INDEX uq_alert_groups_open_scope
    ON alert_groups (scope_type, scope_value)
    WHERE status = 'OPEN';

ALTER TABLE alerts
    ADD CONSTRAINT fk_alerts_group
    FOREIGN KEY (group_id) REFERENCES alert_groups (group_id) ON DELETE SET NULL;

CREATE INDEX idx_alerts_group      ON alerts (group_id);
CREATE INDEX idx_alert_groups_open ON alert_groups (status, scope_type, scope_value);

CREATE TABLE cases (
    case_id               BIGSERIAL     PRIMARY KEY,
    alert_id              BIGINT        NOT NULL,
    assigned_to           BIGINT,
    status                VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    priority              VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    investigation_result  VARCHAR(50),
    opened_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at             TIMESTAMPTZ,
    CONSTRAINT uq_cases_alert UNIQUE (alert_id),
    CONSTRAINT ck_cases_status   CHECK (status IN ('OPEN','UNDER_REVIEW','CONFIRMED','FALSE_POSITIVE','CLOSED')),
    CONSTRAINT ck_cases_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT fk_cases_alert FOREIGN KEY (alert_id)
        REFERENCES alerts (alert_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cases_assignee FOREIGN KEY (assigned_to)
        REFERENCES users (user_id) ON DELETE SET NULL
);

CREATE TABLE case_comments (
    comment_id   BIGSERIAL     PRIMARY KEY,
    case_id      BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    content      TEXT          NOT NULL,
    comment_type VARCHAR(20)   NOT NULL DEFAULT 'NOTE',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_cc_type CHECK (comment_type IN ('NOTE','EVIDENCE','DECISION')),
    CONSTRAINT fk_cc_case FOREIGN KEY (case_id)
        REFERENCES cases (case_id) ON DELETE CASCADE,
    CONSTRAINT fk_cc_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE RESTRICT
);

CREATE TABLE audit_logs (
    audit_id     BIGSERIAL     PRIMARY KEY,
    user_id      BIGINT,
    action       VARCHAR(50)   NOT NULL,
    entity_type  VARCHAR(50)   NOT NULL,
    entity_id    BIGINT        NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    ip_address   VARCHAR(50),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 5. INDEX (performances)
-- ------------------------------------------------------------

CREATE INDEX idx_accounts_customer      ON accounts (customer_id);
CREATE INDEX idx_tx_account_date        ON transactions (account_id, transaction_date);
CREATE INDEX idx_tx_merchant            ON transactions (merchant_id);
CREATE INDEX idx_tx_date                ON transactions (transaction_date);
CREATE INDEX idx_ra_transaction         ON risk_assessments (transaction_id);
CREATE INDEX idx_rf_assessment          ON risk_factors (assessment_id);
CREATE INDEX idx_rf_rule                ON risk_factors (rule_id);
CREATE INDEX idx_alerts_status          ON alerts (status);
CREATE INDEX idx_alerts_assignee        ON alerts (assigned_to);
CREATE INDEX idx_alerts_created         ON alerts (created_at);
CREATE INDEX idx_cases_status           ON cases (status);
CREATE INDEX idx_cases_assignee         ON cases (assigned_to);
CREATE INDEX idx_case_comments_case     ON case_comments (case_id);
CREATE INDEX idx_audit_entity           ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_created          ON audit_logs (created_at);
CREATE INDEX idx_audit_user             ON audit_logs (user_id);
CREATE INDEX idx_rp_features            ON risk_profiles USING GIN (feature_snapshot);

-- ------------------------------------------------------------
-- 6. DROITS pour le role applicatif finrisk
-- ------------------------------------------------------------

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO finrisk;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO finrisk;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO finrisk;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO finrisk;

-- ------------------------------------------------------------
-- 7. SEED : catalogue des regles (doc section 8)
--    REGLE DE POIDS : la somme des poids est plafonnee a 1.35 =>
--    score = somme x 100, plafonne lui-meme a 100 par le moteur.
--
--    RULE-012 (structuration) pese 0.30, PLUS que n'importe quel signal
--    contextuel, et c'est volontaire : un fraudeur qui fractionne 1000 TND
--    en 10 virements de 100 ne declenche AUCUNE regle de montant (chacun
--    reste sous les 5000 TND de RULE-001) ni l'ecart-type. Sans cette regle
--    le score tombe a ~25/100 (LOW) et l'argent sort. A 0.30 elle atteint a
--    elle seule la zone d'action (30 -> MEDIUM/MONITORING) : c'est le seul
--    cas ou un signal unique doit pouvoir declarer une operation, car la
--    structuration est un predicate PRIMAIRE de l'AML, pas un signal
--    contextuel.
--
--    REEQUILIBRAGE v2 (demo) : RULE-002 seuil 10 -> 5 (une rafale de 6 tx/24h
--    est deja suspecte) et RULE-008 poids 0.05 -> 0.10 (le cumul 24h est le
--    signal AML le plus souvent seul a sauter, il etait sous-pese).
-- ------------------------------------------------------------

INSERT INTO risk_rules (rule_code, rule_name, risk_category, comparison_operator, threshold_value, weight, severity) VALUES
    ('RULE-001', 'Montant de transaction eleve',                'AMOUNT',    '>',   5000.00, 0.25, 'HIGH'),
    ('RULE-002', 'Velicite de transaction anormale',            'VELOCITY',  '>',   5.00,    0.15, 'HIGH'),
    ('RULE-003', 'Pays inhabituel',                             'GEOGRAPHIC','=',   NULL,    0.10, 'MEDIUM'),
    ('RULE-004', 'Heure de transaction inhabituelle',           'TIME',      '<=',  5.00,    0.05, 'LOW'),
    ('RULE-005', 'Marchand recent',                             'BEHAVIOR',  '<=',  30.00,   0.05, 'MEDIUM'),
    ('RULE-006', 'Forte deviation comportementale',             'BEHAVIOR',  '>',   2.50,    0.05, 'HIGH'),
    ('RULE-007', 'Montant superieur au P95 du client',          'AMOUNT',    '>',   NULL,    0.10, 'MEDIUM'),
    ('RULE-008', 'Cumul anormal sur 24 h',                      'HISTORY',   '>',   3.00,    0.10, 'MEDIUM'),
    ('RULE-009', 'Transaction dupliquee en rafale',             'BEHAVIOR',  '<=',  10.00,   0.05, 'MEDIUM'),
    ('RULE-010', 'Tentatives repetees en echec',                'VELOCITY',  '>=',  3.00,    0.05, 'MEDIUM'),
    ('RULE-011', 'Voyage geographique impossible',              'GEOGRAPHIC','<=',  180.00,  0.10, 'HIGH'),
    ('RULE-012', 'Fractionnement sous le seuil de declaration', 'HISTORY',   '>',   3.00,    0.30, 'HIGH');

-- ------------------------------------------------------------
-- 8. SEED : utilisateurs de demonstration (jalon M4, hashs M5)
--    alert-case-service ecrit case_comments.user_id / audit_logs.user_id
--    (FK NOT NULL ou SET NULL) : il faut des lignes existantes.
--    Depuis M5, ce sont de VRAIS hashs BCrypt (mêmes generated par
--    BCryptPasswordEncoder, facteur 10, salage aleatoire) — voir
--    docs/auth-service.md. Le bootstrap remplace les identifiants initiaux
--    dans les bases operationnelles. Les fixtures restent utilisables en test.
--    Un hash meme pour un mot de passe identique serait une fuite de
--    salage : chaque ligne ci-dessous a ete generee independamment.
-- ------------------------------------------------------------

INSERT INTO users (user_id, username, email, password_hash, role) VALUES
    (1, 'admin',    'admin@finrisk.tn',
        '$2a$10$UO4kDGIRn.5toxhaak8pVe/dYF9vgU8H3ysRebbmYKrY34EURPc4q', 'ADMIN'),
    (2, 'analyste', 'analyste@finrisk.tn',
        '$2a$10$uxdRSRRP0eS57NvTSt6dZuWAVYGdzAY7.Yz4raJbFOW8FrOp87sEe', 'ANALYST');

-- Les IDs 1 et 2 sont fournis a la main : on recale la sequence pour que
-- la prochaine insertion (auth-service en M5) parte au-dessus, sinon collision.
SELECT setval(pg_get_serial_sequence('users', 'user_id'), (SELECT MAX(user_id) FROM users));
