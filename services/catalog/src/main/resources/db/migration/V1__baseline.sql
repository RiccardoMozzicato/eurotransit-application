-- Catalog schema (read store, tolerant of staleness) = initial database schema that Flyway will create on the postgres profile when the service starts for the first time. Hibernate will only validate it

CREATE TABLE products (
    product_id  VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    price_cents BIGINT       NOT NULL CHECK (price_cents >= 0)
);
