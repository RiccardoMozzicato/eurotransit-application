-- Orders schema: the saga aggregate + the TRANSACTIONAL OUTBOX
-- Flyway owns this schema on the postgres profile; Hibernate only validates

CREATE TABLE orders (
    id           UUID        PRIMARY KEY,
    product_id   VARCHAR(64) NOT NULL,
    qty          INTEGER     NOT NULL CHECK (qty > 0),
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    status       VARCHAR(16) NOT NULL,
    reason       VARCHAR(32),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

-- The outbox: events are INSERTed here in the SAME transaction as the business change (no dual-write); the relay publishes unpublished rows to Kafka and stamps published_at
CREATE TABLE outbox_events (
    id           UUID          PRIMARY KEY,
    topic        VARCHAR(64)   NOT NULL,
    aggregate_id VARCHAR(64)   NOT NULL,
    payload      VARCHAR(4000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    published_at TIMESTAMPTZ
);

-- Partial index: the relay only ever scans unpublished rows (kept tiny by definition)
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
