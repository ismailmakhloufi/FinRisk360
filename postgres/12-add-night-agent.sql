-- ============================================================
-- FinRisk360 - Migration 12 : AGENT NOCTURNE (night agent)
--
-- PROBLEME : l'analyste n'est pas disponible 24h/24. La file
-- EN ATTENTE reste bloquee la nuit : personne ne tranche, et le
-- lendemain la file a grossi sans que rien n'ait ete fait.
--
-- SOLUTION : un agent (job planifie dans alert-case-service,
-- 17h00->09h00) analyse chaque transaction en attente et tranche
-- SELON UN DESIGN HYBRIDE :
--
--   verdict moteur (règles, score)  +  avis IA (insight engine)
--   --------------------------------   -------------------------
--   CRITICAL (>=85)                 -> toujours LAISSE a l'analyste
--   IA = FRAUDE_SUSPECTE            -> LAISSE (l'IA signale)
--   LOW/MEDIUM + IA = NORMAL        -> VALIDE (COMPLETED)
--   LOW/MEDIUM + IA = INCERTAIN     -> LAISSE (hésitation)
--   HIGH + IA = NORMAL              -> LAISSE (divergence signalee)
--
-- AUCUNE annulation automatique : l'agent ne detruit jamais
-- d'argent. Il valide ou il attend — rien d'autre.
--
-- Chaque decision est ECRITE ici (rapport du matin) + tracee en
-- audit_logs (NIGHT_AGENT_VALIDATED / NIGHT_AGENT_DEFERRED) :
-- l'analyste voit le lendemain exactement ce que l'agent a fait.
--
-- ddl-auto = validate : tout est cree ci-dessous.
-- A appliquer avec : psql -v ON_ERROR_STOP=1
--   (finrisk, finrisk_demo, finrisk_test)
-- ============================================================

CREATE TABLE night_agent_decisions (
    decision_id     BIGSERIAL     PRIMARY KEY,
    transaction_id  BIGINT        NOT NULL,
    assessment_id   BIGINT,
    alert_id        BIGINT,
    amount          DECIMAL(15,2),
    risk_score      DECIMAL(5,2),
    risk_level      VARCHAR(20),
    engine_decision VARCHAR(30),
    ai_verdict      VARCHAR(30),
    ai_confidence   DOUBLE PRECISION,
    -- VALIDE    : l'agent a valide la transaction (PENDING -> COMPLETED)
    -- LAISSE   : reste EN ATTENTE pour l'analyste, avec motif
    agent_action    VARCHAR(20)   NOT NULL,
    agent_reason    VARCHAR(500)  NOT NULL,
    run_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_night_agent_action  CHECK (agent_action IN ('VALIDATED','LEFT_PENDING')),
    CONSTRAINT ck_night_agent_level   CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_night_agent_verdict CHECK (ai_verdict IN ('NORMAL','INCERTAIN','FRAUDE_SUSPECTE'))
);

-- Le rapport du matin : derniere execution d'abord.
CREATE INDEX idx_night_agent_run_at ON night_agent_decisions (run_at DESC, decision_id DESC);

-- ============================================================
-- DROITS (convention 02-schema.sql §fin) : la table et sa sequence
-- sont creees ci-dessus par `postgres`, alors que l'application se
-- connecte en `finrisk`. Sans ce GRANT, SELECT/INSERT echouent —
-- finrisk_demo et finrisk_test l'ont prouve en retournant
-- « permission denied » la ou finrisk (grante a la main) marchait.
-- GRANT idempotent : reapplication sans danger.
-- ============================================================
GRANT ALL PRIVILEGES ON TABLE night_agent_decisions TO finrisk;
GRANT USAGE, SELECT ON SEQUENCE public.night_agent_decisions_decision_id_seq TO finrisk;
