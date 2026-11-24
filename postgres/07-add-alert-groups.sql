-- ============================================================
-- FinRisk360 - Migration 07 : agrégation et escalade des alertes
--
-- PROBLEME : 10 virements de 100 TND ouvrent 10 alertes LOW
-- INDEPENDANTES. Chacune prise isolement est anodine, la sévérité
-- est figée à la création (AlertService.severityFrom), et aucun
-- groupement n'existe : l'analyste voit du bruit, jamais le
-- schéma. C'est précisément ce que la règle RULE-012 (migration
-- 06) commence à corriger côté SCORE, mais le moteur ne peut pas
-- tout porter : la lecture par l'analyste a besoin d'un agrégat.
--
-- SOLUTION : une table alert_groups. À chaque création d'alerte,
-- on résout la transaction (account-service / transaction-service,
-- en API — règle M0, jamais de SQL hors service) et on attache
-- l'alerte au groupe ouvert sur le MÊME périmètre (ACCOUNT) et
-- dans la même fenêtre glissante. Le groupe recalculé :
--   - related_count  : nombre d'alertes apparentées ;
--   - total_amount   : cumul des montants ;
--   - severity       : MAX des sévérités individuelles, plancher
--                      par le compteur (>=5 -> MEDIUM,
--                      >=10 -> HIGH).
-- Chaque alerte garde SA sévérité individuelle : on n'altère pas
-- le verdict du moteur, on y ajoute une couche de lecture.
--
-- alerts.amount : montant de la transaction copié à la création.
-- Sans lui, il faudrait re-interroger transaction-service pour
-- afficher un cumul, ou le recalculer à chaque affichage.
--
-- ddl-auto = validate : tout est cree ci-dessous.
-- A appliquer avec : psql -v ON_ERROR_STOP=1
--   (finrisk, finrisk_demo, finrisk_test)
-- ============================================================

-- 1. Montant de la transaction, copie a la creation de l'alerte.
ALTER TABLE alerts ADD COLUMN amount DECIMAL(15,2);

-- 2. Le groupe auquel l'alerte appartient (NULL = non agregee).
ALTER TABLE alerts ADD COLUMN group_id BIGINT;

-- 3. Les groupes eux-memes.
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
    -- locking a faire echouer sous rafale. La colonne reste utile
    -- si une ecriture JPA classique est ajoutee plus tard.
    version         BIGINT        NOT NULL DEFAULT 0,
    -- UN SEUL groupe ouvert par perimetre : c'est cette contrainte
    -- PARTIELLE qui garanti l'invariant, pas un verrou applicatif.
    -- Elle rend possible INSERT ... ON CONFLICT DO NOTHING, qui
    -- n'abort PAS la transaction PostgreSQL quand un concurrent a
    -- deja cree le groupe (un INSERT nu le ferait, et tuerait
    -- l'alerte avec).
    CONSTRAINT ck_ag_scope   CHECK (scope_type IN ('ACCOUNT','CUSTOMER','BENEFICIARY')),
    CONSTRAINT ck_ag_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_ag_status  CHECK (status IN ('OPEN','RESOLVED','FALSE_POSITIVE'))
);

-- Index unique PARTIEL : un seul groupe ouvert par perimetre. Les groupes
-- clotures (RESOLVED / FALSE_POSITIVE) ne le disputent pas, donc deux episodes
-- espaces dans le temps cohabitent sans se gêner.
CREATE UNIQUE INDEX uq_alert_groups_open_scope
    ON alert_groups (scope_type, scope_value)
    WHERE status = 'OPEN';

ALTER TABLE alerts
    ADD CONSTRAINT fk_alerts_group
    FOREIGN KEY (group_id) REFERENCES alert_groups (group_id) ON DELETE SET NULL;

CREATE INDEX idx_alerts_group      ON alerts (group_id);
CREATE INDEX idx_alert_groups_open ON alert_groups (status, scope_type, scope_value);
