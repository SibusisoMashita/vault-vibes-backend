-- Stokvel configuration defaults
INSERT INTO stokvel_config (total_shares, share_price)
VALUES (20, 1000.00)
ON CONFLICT DO NOTHING;

-- Borrowing configuration defaults
INSERT INTO borrowing_config (interest_rate)
VALUES (20.00)
ON CONFLICT DO NOTHING;

-- Add cycle configuration to stokvel_config
ALTER TABLE stokvel_config
    ADD COLUMN cycle_months          INT            NOT NULL DEFAULT 12,
    ADD COLUMN monthly_contribution  NUMERIC(19,2)  NOT NULL DEFAULT 500.00,
    ADD COLUMN cycle_start_date      DATE           NOT NULL DEFAULT '2025-01-01';

UPDATE stokvel_config
SET cycle_months         = 12,
    monthly_contribution = 500.00,
    cycle_start_date     = '2025-01-01';