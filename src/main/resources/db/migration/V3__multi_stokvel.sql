-- ==========================================
-- STOKVELS (MULTI-GROUP SUPPORT)
-- ==========================================

-- 1. Create the stokvels table
CREATE TABLE stokvels (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_stokvels_updated_at
    BEFORE UPDATE ON stokvels
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_stokvels_status ON stokvels (status);


-- 2. Seed "Vault Vibes" as the founding stokvel (fixed UUID for referential stability)
INSERT INTO stokvels (id, name, description, status)
VALUES ('a0000000-0000-0000-0000-000000000001', 'Vault Vibes', 'Original Vault Vibes stokvel', 'ACTIVE');


-- 3. Add stokvel_id to users
ALTER TABLE users
    ADD COLUMN stokvel_id UUID REFERENCES stokvels(id);

-- Assign all existing users to Vault Vibes
UPDATE users
SET stokvel_id = 'a0000000-0000-0000-0000-000000000001';

CREATE INDEX idx_users_stokvel_id ON users (stokvel_id);


-- 4. Add stokvel_id to stokvel_config (one config row per stokvel)
ALTER TABLE stokvel_config
    ADD COLUMN stokvel_id UUID REFERENCES stokvels(id);

UPDATE stokvel_config
SET stokvel_id = 'a0000000-0000-0000-0000-000000000001';

CREATE INDEX idx_stokvel_config_stokvel_id ON stokvel_config (stokvel_id);


-- 5. Add stokvel_id to borrowing_config (one config row per stokvel)
ALTER TABLE borrowing_config
    ADD COLUMN stokvel_id UUID REFERENCES stokvels(id);

UPDATE borrowing_config
SET stokvel_id = 'a0000000-0000-0000-0000-000000000001';

CREATE INDEX idx_borrowing_config_stokvel_id ON borrowing_config (stokvel_id);


-- 6. Add stokvel_id to ledger_entries
--    Needed for SYSTEM entries (BANK_INTEREST etc.) which have no user_id
ALTER TABLE ledger_entries
    ADD COLUMN stokvel_id UUID REFERENCES stokvels(id);

-- User-linked entries: derive stokvel from their user
UPDATE ledger_entries le
SET stokvel_id = u.stokvel_id
FROM users u
WHERE le.user_id = u.id;

-- SYSTEM entries (user_id IS NULL): assign to Vault Vibes
UPDATE ledger_entries
SET stokvel_id = 'a0000000-0000-0000-0000-000000000001'
WHERE user_id IS NULL;

CREATE INDEX idx_ledger_entries_stokvel_id ON ledger_entries (stokvel_id);
