-- ==========================================
-- EXTENSIONS
-- ==========================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ==========================================
-- UPDATED_AT TRIGGER FUNCTION
-- ==========================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ==========================================
-- USERS
-- ==========================================

CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number VARCHAR(25)  NOT NULL UNIQUE
                     CHECK (phone_number ~ '^\+[1-9]\d{1,14}$'),
    email        VARCHAR(255) UNIQUE,
    full_name    VARCHAR(255) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','ACTIVE','SUSPENDED')),
    role         VARCHAR(20)  NOT NULL DEFAULT 'MEMBER'
                     CHECK (role IN ('MEMBER','TREASURER','CHAIRPERSON','ADMIN')),
    cognito_id              VARCHAR(255) UNIQUE,
    onboarding_completed    BOOLEAN      NOT NULL DEFAULT FALSE,
    onboarding_completed_at TIMESTAMPTZ,
    onboarding_version      INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_cognito_id
    ON users (cognito_id);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();


-- ==========================================
-- SHARES
-- ==========================================

CREATE TABLE shares (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID           NOT NULL,
    share_units    NUMERIC(19,4)  NOT NULL CHECK (share_units >= 0),
    price_per_unit NUMERIC(19,4)  NOT NULL DEFAULT 0,
    purchased_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_shares_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_shares_user_id
    ON shares (user_id);


-- ==========================================
-- CONTRIBUTIONS
-- ==========================================

CREATE TABLE contributions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID          NOT NULL,
    amount               NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    contribution_date    DATE          NOT NULL,
    notes                TEXT,
    proof_of_payment_url TEXT,
    proof_file_type      VARCHAR(10),
    rejection_reason     TEXT,
    verification_status  VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                             CHECK (verification_status IN ('PENDING','VERIFIED','REJECTED')),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_contributions_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_contributions_user_id
    ON contributions (user_id);

CREATE INDEX idx_contributions_verification_status
    ON contributions (verification_status);


-- ==========================================
-- LOANS
-- ==========================================

CREATE TABLE loans (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID          NOT NULL,
    principal_amount NUMERIC(19,2) NOT NULL CHECK (principal_amount > 0),
    interest_rate    NUMERIC(5,2)  NOT NULL DEFAULT 20.00
                         CHECK (interest_rate >= 0),
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING','ACTIVE','APPROVED','REJECTED','REPAID')),
    issued_at        TIMESTAMPTZ,
    due_at           TIMESTAMPTZ,
    amount_repaid    NUMERIC(19,2) NOT NULL DEFAULT 0
                         CHECK (amount_repaid >= 0),
    term_months      INTEGER       NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_loans_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_loans_user_id
    ON loans (user_id);

CREATE INDEX idx_loans_status
    ON loans (status);


-- ==========================================
-- LEDGER ENTRIES (SOURCE OF TRUTH)
-- ==========================================

CREATE TABLE ledger_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    entry_type  VARCHAR(60)   NOT NULL,
    entry_scope VARCHAR(10)   NOT NULL DEFAULT 'USER'
                    CHECK (entry_scope IN ('USER','SYSTEM')),
    amount      NUMERIC(19,2) NOT NULL,
    reference   VARCHAR(120),
    description TEXT,
    posted_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ledger_entries_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_ledger_entries_user_id
    ON ledger_entries (user_id);

CREATE INDEX idx_ledger_entries_posted_at
    ON ledger_entries (posted_at);

CREATE INDEX idx_ledger_entries_type
    ON ledger_entries (entry_type);

-- Composite index for the most common financial query: entries per member ordered by time
CREATE INDEX idx_ledger_user_time
    ON ledger_entries (user_id, posted_at DESC);


-- ==========================================
-- DISTRIBUTIONS
-- ==========================================

CREATE TABLE distributions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL,
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,
    distributed_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_distributions_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_distributions_user_id
    ON distributions (user_id);


-- ==========================================
-- INVITATIONS
-- ==========================================

CREATE TABLE invitations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL,
    invited_by UUID REFERENCES users (id),
    status     VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','SENT','ACCEPTED','EXPIRED','CANCELLED')),
    resent_at  TIMESTAMPTZ,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_invitations_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_invitations_user_id
    ON invitations (user_id);

CREATE INDEX idx_invitations_status
    ON invitations (status);

-- Only one active invitation per user at a time.
CREATE UNIQUE INDEX uq_active_invite_user
    ON invitations (user_id)
    WHERE status IN ('PENDING', 'SENT');


-- ==========================================
-- STOKVEL CONFIG
-- ==========================================

CREATE TABLE stokvel_config (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    total_shares NUMERIC(19,4) NOT NULL DEFAULT 240,
    share_price  NUMERIC(19,2) NOT NULL DEFAULT 5000.00,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_stokvel_config_updated_at
    BEFORE UPDATE ON stokvel_config
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();


-- ==========================================
-- BORROWING CONFIG
-- ==========================================

CREATE TABLE borrowing_config (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interest_rate NUMERIC(5,2) NOT NULL DEFAULT 20.00
                      CHECK (interest_rate >= 0),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_borrowing_config_updated_at
    BEFORE UPDATE ON borrowing_config
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();


-- ==========================================
-- POOL STATE SNAPSHOT
-- ==========================================

CREATE TABLE pool_state (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    total_members       INTEGER       NOT NULL DEFAULT 0,
    total_shares        NUMERIC(19,4) NOT NULL DEFAULT 0,
    shares_allocated    NUMERIC(19,4) NOT NULL DEFAULT 0,
    capital_committed   NUMERIC(19,2) NOT NULL DEFAULT 0,
    capital_received    NUMERIC(19,2) NOT NULL DEFAULT 0,
    liquidity_available NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_borrowed      NUMERIC(19,2) NOT NULL DEFAULT 0,
    per_share_value     NUMERIC(19,4) NOT NULL DEFAULT 0,
    active_borrowings   INTEGER       NOT NULL DEFAULT 0,
    computed_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Enforce single snapshot row — UPDATE to refresh, never INSERT a second row
CREATE OR REPLACE FUNCTION enforce_single_pool_state()
RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT COUNT(*) FROM pool_state) >= 1 THEN
        RAISE EXCEPTION 'pool_state must contain exactly one row — use UPDATE to refresh the snapshot.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pool_state_singleton
    BEFORE INSERT ON pool_state
    FOR EACH ROW
    EXECUTE FUNCTION enforce_single_pool_state();


-- ==========================================
-- CONTRIBUTION MONTH HELPER
-- ==========================================

CREATE OR REPLACE FUNCTION contribution_year_month(d DATE)
RETURNS INT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT (EXTRACT(YEAR FROM d)::INT * 100) + EXTRACT(MONTH FROM d)::INT
$$;

-- One non-rejected contribution per member per calendar month.
-- REJECTED contributions are excluded so a member can re-submit after rejection.
CREATE UNIQUE INDEX uq_contributions_user_month
    ON contributions (user_id, contribution_year_month(contribution_date))
    WHERE verification_status <> 'REJECTED';
