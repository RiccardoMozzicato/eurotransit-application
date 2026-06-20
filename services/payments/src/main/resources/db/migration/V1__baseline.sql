-- Payments schema (Pillar B: strict idempotency — must not double-charge).
-- Flyway owns this schema on the postgres profile; Hibernate only validates.

CREATE TABLE payment_authorizations (
    id           UUID        PRIMARY KEY,

    -- The idempotency key of the money path: AT MOST ONE authorization outcome per order. A duplicated/retried authorize either replays the stored outcome or loses this constraint race and rolls back its whole transaction (charge included)
    order_id     VARCHAR(64) NOT NULL,

    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),

    status       VARCHAR(16) NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL,
    
    -- This UNIQUE(order_id) IS the idempotency record: it is what enforces "must not double-charge"
    CONSTRAINT uq_payment_auth_order UNIQUE (order_id)
);
