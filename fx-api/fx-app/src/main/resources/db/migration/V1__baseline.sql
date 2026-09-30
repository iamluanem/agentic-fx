-- =====================================================================================================
-- Agentic FX — baseline schema
--
-- Conventions
--   * Money: NUMERIC(19,2) + a currency column. Rates: NUMERIC(18,8).
--   * Mutable aggregates carry a `version` column for JPA optimistic locking.
--   * Facts (quotes, policy evaluations, agent decisions, conversions, audit events) are append-only:
--     a trigger rejects UPDATE/DELETE/TRUNCATE, so immutability does not depend on application code.
--   * Duplicate execution is prevented by the database, not only by the application:
--       - one conversion per proposal            (fx_conversions.proposal_id UNIQUE)
--       - one open proposal per goal             (partial unique index)
--       - one ACTIVE mandate version per goal    (partial unique index)
--       - idempotency keys per user              (idempotency_keys PK)
-- =====================================================================================================

CREATE DOMAIN currency_code AS CHAR(3) CHECK (VALUE IN ('BRL', 'EUR', 'USD', 'GBP'));
CREATE DOMAIN money_amount  AS NUMERIC(19, 2);
CREATE DOMAIN fx_rate       AS NUMERIC(18, 8) CHECK (VALUE > 0);

CREATE FUNCTION reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- ----------------------------------------------------------------------------------------------- users

CREATE TABLE users
(
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,
    display_name  VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    roles         TEXT[]       NOT NULL CHECK (roles <@ ARRAY ['CUSTOMER','OPERATOR','AGENT']::TEXT[]),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE currency_accounts
(
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL UNIQUE REFERENCES users (id),
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE balances
(
    account_id UUID          NOT NULL REFERENCES currency_accounts (id),
    currency   currency_code NOT NULL,
    available  money_amount  NOT NULL CHECK (available >= 0),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, currency)
);

-- ------------------------------------------------------------------------------------------- goals

CREATE TABLE fx_goals
(
    id                  UUID PRIMARY KEY,
    user_id             UUID          NOT NULL REFERENCES users (id),
    name                VARCHAR(120)  NOT NULL,
    source_currency     currency_code NOT NULL,
    target_currency     currency_code NOT NULL,
    target_amount       money_amount  NOT NULL CHECK (target_amount > 0),
    deadline            DATE          NOT NULL,
    max_budget          money_amount  NOT NULL CHECK (max_budget > 0),
    strategy_type       VARCHAR(20)   NOT NULL CHECK (strategy_type IN ('TARGET_RATE', 'ACCUMULATE', 'DEADLINE_AWARE')),
    target_rate         fx_rate,
    planned_tranches    INT           NOT NULL CHECK (planned_tranches BETWEEN 1 AND 50),
    min_improvement_bps INT           NOT NULL CHECK (min_improvement_bps BETWEEN 0 AND 5000),
    status              VARCHAR(12)   NOT NULL
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    acquired_amount     money_amount  NOT NULL DEFAULT 0 CHECK (acquired_amount >= 0),
    spent_amount        money_amount  NOT NULL DEFAULT 0 CHECK (spent_amount >= 0),
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CHECK (source_currency <> target_currency),
    CHECK (strategy_type <> 'TARGET_RATE' OR target_rate IS NOT NULL)
);

CREATE INDEX ix_fx_goals_user ON fx_goals (user_id, created_at DESC);
CREATE INDEX ix_fx_goals_monitorable ON fx_goals (source_currency, target_currency) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------------------- mandates

CREATE TABLE agent_mandates
(
    id                     UUID          NOT NULL,
    version                INT           NOT NULL CHECK (version >= 1),
    goal_id                UUID          NOT NULL REFERENCES fx_goals (id),
    user_id                UUID          NOT NULL REFERENCES users (id),
    source_currency        currency_code NOT NULL,
    target_currency        currency_code NOT NULL,
    max_total_spend        money_amount  NOT NULL CHECK (max_total_spend > 0),
    max_transaction_amount money_amount  NOT NULL CHECK (max_transaction_amount > 0),
    approval_threshold     money_amount CHECK (approval_threshold >= 0),
    allow_partial          BOOLEAN       NOT NULL,
    execution_mode         VARCHAR(20)   NOT NULL CHECK (execution_mode IN ('HUMAN_IN_THE_LOOP', 'AUTONOMOUS')),
    valid_until            DATE          NOT NULL,
    status                 VARCHAR(12)   NOT NULL
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'SUPERSEDED', 'REVOKED')),
    created_at             TIMESTAMPTZ   NOT NULL,
    activated_at           TIMESTAMPTZ,
    ended_at               TIMESTAMPTZ,
    row_version            BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id, version),
    CHECK (max_transaction_amount <= max_total_spend),
    CHECK (source_currency <> target_currency)
);

-- A goal can have at most one mandate version in force at any time.
CREATE UNIQUE INDEX ux_agent_mandates_one_active_per_goal ON agent_mandates (goal_id) WHERE status = 'ACTIVE';

-- Terms of a mandate version are immutable once written; only lifecycle columns may change.
CREATE FUNCTION protect_mandate_terms() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Mandate versions cannot be deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (NEW.id, NEW.version, NEW.goal_id, NEW.user_id, NEW.source_currency, NEW.target_currency,
        NEW.max_total_spend, NEW.max_transaction_amount, NEW.approval_threshold, NEW.allow_partial,
        NEW.execution_mode, NEW.valid_until, NEW.created_at)
        IS DISTINCT FROM
       (OLD.id, OLD.version, OLD.goal_id, OLD.user_id, OLD.source_currency, OLD.target_currency,
        OLD.max_total_spend, OLD.max_transaction_amount, OLD.approval_threshold, OLD.allow_partial,
        OLD.execution_mode, OLD.valid_until, OLD.created_at) THEN
        RAISE EXCEPTION 'Mandate terms are immutable; create a new version instead'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_mandates_protect
    BEFORE UPDATE OR DELETE
    ON agent_mandates
    FOR EACH ROW
EXECUTE FUNCTION protect_mandate_terms();

-- ------------------------------------------------------------------------------------------ market

CREATE TABLE fx_rate_snapshots
(
    id          BIGSERIAL PRIMARY KEY,
    pair        CHAR(7)     NOT NULL,
    rate        fx_rate     NOT NULL,
    source      VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_fx_rate_snapshots_pair_time ON fx_rate_snapshots (pair, observed_at DESC);

CREATE TABLE fx_quotes
(
    id              UUID PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES users (id),
    source_currency currency_code NOT NULL,
    target_currency currency_code NOT NULL,
    rate            fx_rate       NOT NULL,
    source_amount   money_amount  NOT NULL CHECK (source_amount > 0),
    fee             money_amount  NOT NULL CHECK (fee >= 0),
    target_amount   money_amount  NOT NULL CHECK (target_amount >= 0),
    provider        VARCHAR(40)   NOT NULL,
    quoted_at       TIMESTAMPTZ   NOT NULL,
    expires_at      TIMESTAMPTZ   NOT NULL,
    CHECK (expires_at > quoted_at),
    CHECK (fee < source_amount)
);

-- ---------------------------------------------------------------------------------------- execution

CREATE TABLE execution_proposals
(
    id               UUID PRIMARY KEY,
    goal_id          UUID         NOT NULL REFERENCES fx_goals (id),
    user_id          UUID         NOT NULL REFERENCES users (id),
    mandate_id       UUID         NOT NULL,
    mandate_version  INT          NOT NULL,
    quote_id         UUID         NOT NULL REFERENCES fx_quotes (id),
    status           VARCHAR(20)  NOT NULL CHECK (status IN
        ('PROPOSED', 'AWAITING_APPROVAL', 'APPROVED', 'REJECTED', 'EXECUTING', 'COMPLETED', 'FAILED')),
    origin           VARCHAR(20)  NOT NULL CHECK (origin IN ('STRATEGY_ENGINE', 'LLM_AGENT')),
    explanation      JSONB        NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    approved_rate    fx_rate,
    rejection_source VARCHAR(20) CHECK (rejection_source IN ('POLICY', 'USER', 'APPROVAL_EXPIRED', 'SUPERSEDED')),
    status_reason    VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    FOREIGN KEY (mandate_id, mandate_version) REFERENCES agent_mandates (id, version),
    UNIQUE (user_id, idempotency_key)
);

-- Serialises execution per goal: a new proposal cannot be opened while another one is in flight.
CREATE UNIQUE INDEX ux_execution_proposals_one_open_per_goal ON execution_proposals (goal_id)
    WHERE status IN ('PROPOSED', 'AWAITING_APPROVAL', 'APPROVED', 'EXECUTING');
CREATE INDEX ix_execution_proposals_goal ON execution_proposals (goal_id, created_at DESC);

CREATE TABLE policy_evaluations
(
    id              UUID PRIMARY KEY,
    proposal_id     UUID         NOT NULL REFERENCES execution_proposals (id),
    mandate_id      UUID         NOT NULL,
    mandate_version INT          NOT NULL,
    decision        VARCHAR(20)  NOT NULL CHECK (decision IN ('ALLOW', 'REQUIRE_APPROVAL', 'DENY')),
    reason_codes    TEXT[]       NOT NULL,
    explanation     VARCHAR(2000) NOT NULL,
    findings        JSONB        NOT NULL,
    policy_version  VARCHAR(40)  NOT NULL,
    quote_id        UUID         NOT NULL REFERENCES fx_quotes (id),
    evaluated_at    TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (mandate_id, mandate_version) REFERENCES agent_mandates (id, version)
);

CREATE INDEX ix_policy_evaluations_proposal ON policy_evaluations (proposal_id, evaluated_at);

CREATE TABLE approval_requests
(
    id           UUID PRIMARY KEY,
    proposal_id  UUID        NOT NULL REFERENCES execution_proposals (id),
    goal_id      UUID        NOT NULL REFERENCES fx_goals (id),
    user_id      UUID        NOT NULL REFERENCES users (id),
    status       VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    decided_at   TIMESTAMPTZ,
    decided_by   UUID REFERENCES users (id),
    note         VARCHAR(500),
    version      BIGINT      NOT NULL DEFAULT 0,
    CHECK (expires_at > requested_at),
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

-- A proposal can have several approval rounds (e.g. re-approval after a rate move), but only one pending.
CREATE UNIQUE INDEX ux_approval_requests_one_pending ON approval_requests (proposal_id) WHERE status = 'PENDING';
CREATE INDEX ix_approval_requests_user_pending ON approval_requests (user_id, requested_at DESC) WHERE status = 'PENDING';

CREATE TABLE fx_conversions
(
    id                 UUID PRIMARY KEY,
    proposal_id        UUID          NOT NULL UNIQUE REFERENCES execution_proposals (id),
    goal_id            UUID          NOT NULL REFERENCES fx_goals (id),
    user_id            UUID          NOT NULL REFERENCES users (id),
    mandate_id         UUID          NOT NULL,
    mandate_version    INT           NOT NULL,
    quote_id           UUID          NOT NULL REFERENCES fx_quotes (id),
    provider           VARCHAR(40)   NOT NULL,
    provider_reference VARCHAR(128)  NOT NULL,
    source_currency    currency_code NOT NULL,
    target_currency    currency_code NOT NULL,
    rate               fx_rate       NOT NULL,
    source_amount      money_amount  NOT NULL CHECK (source_amount > 0),
    fee                money_amount  NOT NULL CHECK (fee >= 0),
    target_amount      money_amount  NOT NULL CHECK (target_amount > 0),
    executed_at        TIMESTAMPTZ   NOT NULL,
    FOREIGN KEY (mandate_id, mandate_version) REFERENCES agent_mandates (id, version),
    UNIQUE (provider, provider_reference)
);

CREATE INDEX ix_fx_conversions_goal ON fx_conversions (goal_id, executed_at DESC);
CREATE INDEX ix_fx_conversions_user ON fx_conversions (user_id, executed_at DESC);

-- ------------------------------------------------------------------------------------------ agent

CREATE TABLE agent_decisions
(
    id          UUID PRIMARY KEY,
    goal_id     UUID         NOT NULL REFERENCES fx_goals (id),
    user_id     UUID         NOT NULL REFERENCES users (id),
    type        VARCHAR(40)  NOT NULL,
    source      VARCHAR(20)  NOT NULL CHECK (source IN ('STRATEGY_ENGINE', 'LLM_AGENT')),
    summary     VARCHAR(1000) NOT NULL,
    explanation JSONB,
    proposal_id UUID REFERENCES execution_proposals (id),
    decided_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_agent_decisions_goal ON agent_decisions (goal_id, decided_at DESC);

-- ------------------------------------------------------------------------------------------ audit

CREATE TABLE audit_events
(
    seq                BIGSERIAL UNIQUE,
    id                 UUID PRIMARY KEY,
    occurred_at        TIMESTAMPTZ   NOT NULL,
    type               VARCHAR(40)   NOT NULL,
    actor              VARCHAR(80)   NOT NULL,
    user_id            UUID          NOT NULL,
    goal_id            UUID,
    mandate_id         UUID,
    mandate_version    INT,
    proposal_id        UUID,
    agent_decision     VARCHAR(1000),
    policy_decision    VARCHAR(20) CHECK (policy_decision IN ('ALLOW', 'REQUIRE_APPROVAL', 'DENY')),
    reason_codes       TEXT[]        NOT NULL DEFAULT '{}',
    quote_id           UUID,
    quote_rate         VARCHAR(30),
    provider           VARCHAR(40),
    requested_amount   money_amount,
    requested_currency currency_code,
    executed_amount    money_amount,
    executed_currency  currency_code,
    summary            VARCHAR(2000) NOT NULL,
    correlation_id     VARCHAR(64),
    details            JSONB         NOT NULL DEFAULT '{}',
    CHECK ((requested_amount IS NULL) = (requested_currency IS NULL)),
    CHECK ((executed_amount IS NULL) = (executed_currency IS NULL))
);

CREATE INDEX ix_audit_events_goal ON audit_events (goal_id, seq);
CREATE INDEX ix_audit_events_user ON audit_events (user_id, seq);

-- --------------------------------------------------------------------------- idempotency & messaging

CREATE TABLE idempotency_keys
(
    user_id         UUID         NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation       VARCHAR(80)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX ix_idempotency_keys_expiry ON idempotency_keys (expires_at);

-- Transactional outbox: domain events are written in the same transaction as state changes and
-- relayed to the broker afterwards, so an event is never lost nor published for a rolled-back change.
CREATE TABLE outbox_events
(
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0
);

CREATE INDEX ix_outbox_events_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;

-- Consumer-side deduplication: a consumer records the event id in the same transaction as its effect.
CREATE TABLE processed_events
(
    consumer     VARCHAR(80) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- ---------------------------------------------------------------------------- append-only enforcement

CREATE TRIGGER trg_fx_quotes_append_only BEFORE UPDATE OR DELETE ON fx_quotes
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_policy_evaluations_append_only BEFORE UPDATE OR DELETE ON policy_evaluations
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_fx_conversions_append_only BEFORE UPDATE OR DELETE ON fx_conversions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_agent_decisions_append_only BEFORE UPDATE OR DELETE ON agent_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_audit_events_no_truncate BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
