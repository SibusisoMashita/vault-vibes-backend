-- Stokvel configuration defaults
INSERT INTO stokvel_config (total_shares, share_price)
VALUES (20, 1000.00)
ON CONFLICT DO NOTHING;

-- Borrowing configuration defaults
INSERT INTO borrowing_config (interest_rate)
VALUES (20.00)
ON CONFLICT DO NOTHING;