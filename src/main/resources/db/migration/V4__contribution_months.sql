-- Allow groups to configure how many months per year members contribute.
-- Default 12. Groups that skip December (for example) would set this to 11.
ALTER TABLE stokvel_config
    ADD COLUMN IF NOT EXISTS contribution_months INT NOT NULL DEFAULT 12
        CHECK (contribution_months BETWEEN 1 AND 12);
