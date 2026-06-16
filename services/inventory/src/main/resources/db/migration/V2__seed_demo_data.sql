-- Demo seed: a few train departures with finite seats (the domain may be reskinned; what matters are the interaction patterns). Useful for the live demo and chaos runs.
INSERT INTO inventory_items (product_id, total_seats, available) VALUES
    ('TRENO-TO-MI-0800', 120, 120),
    ('TRENO-TO-RM-0930',  80,  80),
    ('TRENO-MI-NA-1015', 100, 100),
    ('TRENO-LAST-SEAT-DEMO', 1, 1);
