-- S3-PAY database baseline/hardening migration.
-- S3 owns orchestration state only. Money movement, holds and ledger entries
-- remain exclusively owned by S2-LDG.

CREATE TABLE IF NOT EXISTS transfers (
    id UUID PRIMARY KEY,
    source_account_number VARCHAR(12) NOT NULL,
    dest_account_number VARCHAR(12),
    dest_iban VARCHAR(26),
    source_account_id UUID,
    dest_account_id UUID,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'IRR',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    is_internal BOOLEAN NOT NULL,
    description VARCHAR(256),
    idempotency_key VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    request_hash CHAR(64),
    risk_decision VARCHAR(32),
    risk_score INTEGER,
    external_reference VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE transfers ADD COLUMN IF NOT EXISTS request_hash CHAR(64);

ALTER TABLE transfers ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- request_hash is populated for all new rows. Legacy rows keep NULL and are
-- fingerprinted from their persisted business fields by the service on replay.

-- Existing deployments may already have these tables from schema.sql.
-- Do not silently delete or rewrite duplicate idempotency records: fail the
-- migration so the operator can inspect and reconcile them explicitly.
CREATE UNIQUE INDEX IF NOT EXISTS uq_transfers_idempotency_key
    ON transfers (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_transfers_status_created
    ON transfers (status, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_transfers_correlation
    ON transfers (correlation_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_transfers_external_reference
    ON transfers (external_reference)
    WHERE external_reference IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_amount_positive') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_amount_positive CHECK (amount > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_currency_irr') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_currency_irr CHECK (currency = 'IRR');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_source_account') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_source_account
            CHECK (source_account_number ~ '^[0-9]{10,12}$');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_exactly_one_destination') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_exactly_one_destination
            CHECK ((dest_account_number IS NOT NULL) <> (dest_iban IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_destination_matches_type') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_destination_matches_type
            CHECK (
                (is_internal = TRUE AND dest_account_number IS NOT NULL AND dest_iban IS NULL
                    AND dest_account_number ~ '^[0-9]{10,12}$')
                OR
                (is_internal = FALSE AND dest_iban IS NOT NULL AND dest_account_number IS NULL
                    AND dest_iban ~ '^IR[0-9]{24}$')
            );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_status') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_status CHECK (status IN (
            'PENDING','RISK_CHECKING','RISK_REJECTED','RISK_MANUAL_REVIEW',
            'HOLDING','HOLD_FAILED','EXECUTING','SETTLED','FAILED','REVERSED'
        ));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_risk_decision') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_risk_decision
            CHECK (risk_decision IS NULL OR risk_decision IN ('APPROVED','REJECTED','MANUAL_REVIEW'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_risk_score') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_risk_score
            CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_request_hash') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_request_hash
            CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_idempotency_key_nonblank') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_idempotency_key_nonblank
            CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 255);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_risk_fields_consistent') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_risk_fields_consistent
            CHECK ((risk_decision IS NULL AND risk_score IS NULL)
                OR (risk_decision IS NOT NULL AND risk_score IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_external_reference_type') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_external_reference_type
            CHECK (external_reference IS NULL OR is_internal = FALSE);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfers_version_nonnegative') THEN
        ALTER TABLE transfers ADD CONSTRAINT ck_transfers_version_nonnegative CHECK (version >= 0);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS transfer_events (
    event_seq BIGINT GENERATED BY DEFAULT AS IDENTITY,
    id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_transfer_events PRIMARY KEY (id)
);

ALTER TABLE transfer_events ADD COLUMN IF NOT EXISTS event_seq BIGINT GENERATED BY DEFAULT AS IDENTITY;
UPDATE transfer_events SET payload = '{}'::jsonb WHERE payload IS NULL;
ALTER TABLE transfer_events ALTER COLUMN payload SET DEFAULT '{}'::jsonb;
ALTER TABLE transfer_events ALTER COLUMN payload SET NOT NULL;

ALTER TABLE transfer_events DROP CONSTRAINT IF EXISTS transfer_events_transfer_id_fkey;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_transfer_events_transfer') THEN
        ALTER TABLE transfer_events ADD CONSTRAINT fk_transfer_events_transfer
            FOREIGN KEY (transfer_id) REFERENCES transfers(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_transfer_events_type') THEN
        ALTER TABLE transfer_events ADD CONSTRAINT ck_transfer_events_type CHECK (event_type IN (
            'CREATED','RISK_ASSESSMENT_REQUESTED','AUTHORIZATION_CHECKED','RISK_ASSESSED','FUNDS_HELD',
            'FUNDS_CAPTURED','FUNDS_RELEASED','TRANSFER_EXECUTED',
            'TRANSFER_SETTLED','TRANSFER_FAILED','TRANSFER_REVERSED'
        ));
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_transfer_events_seq ON transfer_events(event_seq);
CREATE INDEX IF NOT EXISTS idx_transfer_events_timeline
    ON transfer_events (transfer_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_transfer_events_type_time
    ON transfer_events (event_type, occurred_at DESC);

-- Append-only audit history: event rows cannot be changed or deleted.
CREATE OR REPLACE FUNCTION reject_transfer_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'transfer_events is append-only; UPDATE/DELETE is forbidden'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS trg_transfer_events_immutable ON transfer_events;
CREATE TRIGGER trg_transfer_events_immutable
    BEFORE UPDATE OR DELETE ON transfer_events
    FOR EACH ROW EXECUTE FUNCTION reject_transfer_event_mutation();

CREATE OR REPLACE FUNCTION set_transfer_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    NEW.version = OLD.version + 1;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_transfers_updated_at ON transfers;
CREATE TRIGGER trg_transfers_updated_at
    BEFORE UPDATE ON transfers
    FOR EACH ROW EXECUTE FUNCTION set_transfer_updated_at();


-- The request identity and financial intent are immutable after creation.
CREATE OR REPLACE FUNCTION protect_transfer_immutable_fields()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.source_account_number IS DISTINCT FROM OLD.source_account_number
       OR NEW.dest_account_number IS DISTINCT FROM OLD.dest_account_number
       OR NEW.dest_iban IS DISTINCT FROM OLD.dest_iban
       OR NEW.source_account_id IS DISTINCT FROM OLD.source_account_id
       OR NEW.dest_account_id IS DISTINCT FROM OLD.dest_account_id
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.is_internal IS DISTINCT FROM OLD.is_internal
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Transfer intent fields are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.risk_decision IS NOT NULL
       AND (NEW.risk_decision IS DISTINCT FROM OLD.risk_decision
            OR NEW.risk_score IS DISTINCT FROM OLD.risk_score) THEN
        RAISE EXCEPTION 'Risk decision is immutable once recorded'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.external_reference IS NOT NULL
       AND NEW.external_reference IS DISTINCT FROM OLD.external_reference THEN
        RAISE EXCEPTION 'External reference is immutable once recorded'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_transfers_immutable_fields ON transfers;
CREATE TRIGGER trg_transfers_immutable_fields
    BEFORE UPDATE ON transfers
    FOR EACH ROW EXECUTE FUNCTION protect_transfer_immutable_fields();

CREATE OR REPLACE FUNCTION protect_transfer_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Transfers cannot be deleted; use compensating workflow'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS trg_transfers_no_delete ON transfers;
CREATE TRIGGER trg_transfers_no_delete
    BEFORE DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION protect_transfer_delete();

CREATE OR REPLACE FUNCTION enforce_transfer_status_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'PENDING' AND NEW.status IN ('RISK_CHECKING','FAILED'))
        OR (OLD.status = 'RISK_CHECKING' AND NEW.status IN
            ('HOLDING','RISK_REJECTED','RISK_MANUAL_REVIEW','FAILED'))
        OR (OLD.status = 'RISK_MANUAL_REVIEW' AND NEW.status IN ('HOLDING','RISK_REJECTED','FAILED'))
        OR (OLD.status = 'HOLDING' AND NEW.status IN ('HOLD_FAILED','EXECUTING','SETTLED','FAILED'))
        OR (OLD.status = 'HOLD_FAILED' AND NEW.status IN ('HOLDING','FAILED'))
        OR (OLD.status = 'EXECUTING' AND NEW.status IN ('SETTLED','FAILED'))
        OR (OLD.status = 'SETTLED' AND NEW.status = 'REVERSED')
    ) THEN
        RAISE EXCEPTION 'Invalid transfer status transition: % -> %', OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_transfers_status_transition ON transfers;
CREATE TRIGGER trg_transfers_status_transition
    BEFORE UPDATE OF status ON transfers
    FOR EACH ROW EXECUTE FUNCTION enforce_transfer_status_transition();

-- Legacy table retained for compatibility with earlier project iterations.
-- The canonical idempotency record is now transfers.idempotency_key + request_hash.


-- Consumer inbox: deduplicates at-least-once Kafka deliveries. The insert is
-- performed in the same PostgreSQL transaction as aggregate/event mutations.
CREATE TABLE IF NOT EXISTS processed_messages (
    consumer_name VARCHAR(64) NOT NULL,
    message_id UUID NOT NULL,
    transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, message_id)
);
CREATE INDEX IF NOT EXISTS idx_processed_messages_transfer
    ON processed_messages (transfer_id, processed_at DESC);


-- Transactional outbox: transfer state and outbound Kafka command are committed
-- in the same PostgreSQL transaction. Delivery is at-least-once; message_id is
-- stable and consumers deduplicate through processed_messages.
CREATE TABLE IF NOT EXISTS outbox_messages (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    topic VARCHAR(249) NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);
CREATE INDEX IF NOT EXISTS idx_outbox_ready
    ON outbox_messages (available_at, created_at, id)
    WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON outbox_messages (aggregate_id, created_at);


-- Exact POST replay snapshot required by GEN-07. The transfer row remains the
-- live aggregate; this table preserves the original create response.
CREATE TABLE IF NOT EXISTS idempotency_responses (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE REFERENCES transfers(id) ON DELETE RESTRICT,
    response_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE OR REPLACE FUNCTION reject_idempotency_response_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Idempotency response snapshots are immutable'
        USING ERRCODE = '55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_idempotency_responses_immutable ON idempotency_responses;
CREATE TRIGGER trg_idempotency_responses_immutable
    BEFORE UPDATE OR DELETE ON idempotency_responses
    FOR EACH ROW EXECUTE FUNCTION reject_idempotency_response_mutation();
