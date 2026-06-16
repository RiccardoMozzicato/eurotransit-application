-- Inventory schema (Pillar B: the contended resource)
-- Flyway owns this schema on the postgres profile; Hibernate only validates

CREATE TABLE inventory_items (
    product_id  VARCHAR(64) PRIMARY KEY,
    total_seats INTEGER     NOT NULL CHECK (total_seats >= 0),
    -- Last line of defence for "never oversell": even a code bug cannot push the seat count below zero (the database refuses the write). The first line is the atomic conditional UPDATE in InventoryItemRepository.tryDecrement
    available   INTEGER     NOT NULL CHECK (available >= 0)
);

CREATE TABLE reservations (
    id         UUID         PRIMARY KEY,
    order_id   VARCHAR(64)  NOT NULL,
    product_id VARCHAR(64)  NOT NULL REFERENCES inventory_items (product_id),
    qty        INTEGER      NOT NULL CHECK (qty > 0),
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    -- The idempotency record of the money path: one reservation outcome per (order, product). Duplicated deliveries either replay the stored outcome or lose this constraint race and roll back their whole transaction (decrement included)
    CONSTRAINT uq_reservations_order_product UNIQUE (order_id, product_id)
);

-- Reconciler sweep: find RESERVED holds whose TTL elapsed
CREATE INDEX idx_reservations_status_expires ON reservations (status, expires_at);
