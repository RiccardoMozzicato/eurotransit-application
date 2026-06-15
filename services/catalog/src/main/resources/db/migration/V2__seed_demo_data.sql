-- Demo seed
-- productIds deliberately match Inventory's seed: Catalog owns names/prices, Inventory owns seat counts for the same products (database-per-service: the two stores share only the productId vocabulary, never each other's tables)

INSERT INTO products (product_id, name, price_cents) VALUES
    ('TRENO-TO-MI-0800', 'Torino -> Milano 08:00', 1500),
    ('TRENO-TO-RM-0930', 'Torino -> Roma 09:30', 4900),
    ('TRENO-MI-NA-1015', 'Milano -> Napoli 10:15', 6900),
    ('TRENO-LAST-SEAT-DEMO', 'Demo: last-seat contention', 1000);
