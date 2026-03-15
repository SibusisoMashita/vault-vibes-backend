-- ==========================================
-- EXTENSIONS
-- ==========================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ==========================================
-- USERS
-- ==========================================

CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number VARCHAR(25)  NOT NULL UNIQUE,
    email        VARCHAR(255) UNIQUE,
    full_name    VARCHAR(255) NOT NULL,
    status       VARCHAR(40)  NOT NULL DEFAULT 'PENDING',
    role         VARCHAR(40)  NOT NULL DEFAULT 'MEMBER',
    cognito_id   VARCHAR(255) UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_cognito_id
    ON users (cognito_id);


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
            ON DELETE CASCADE
);

CREATE INDEX idx_shares_user_id
    ON shares (user_id);


-- ==========================================
-- CONTRIBUTIONS
-- ==========================================

CREATE TABLE contributions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID          NOT NULL,
    amount               NUMERIC(19,2) NOT NULL,
    contribution_date    DATE          NOT NULL,
    notes                TEXT,
    proof_of_payment_url TEXT,
    proof_file_type      VARCHAR(10),
    rejection_reason     TEXT,
    verification_status  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_contributions_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_contributions_user_id
    ON contributions (user_id);


-- ==========================================
-- LOANS
-- ==========================================

CREATE TABLE loans (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID          NOT NULL,
    principal_amount NUMERIC(19,2) NOT NULL,
    interest_rate    NUMERIC(5,2)  NOT NULL DEFAULT 20.00,
    status           VARCHAR(40)   NOT NULL DEFAULT 'PENDING',
    issued_at        TIMESTAMPTZ,
    due_at           TIMESTAMPTZ,
    amount_repaid    NUMERIC(19,2) NOT NULL DEFAULT 0,
    term_months      INTEGER       NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_loans_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_loans_user_id
    ON loans (user_id);


-- ==========================================
-- LEDGER ENTRIES (SOURCE OF TRUTH)
-- ==========================================

CREATE TABLE ledger_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    entry_type  VARCHAR(60)   NOT NULL,
    amount      NUMERIC(19,2) NOT NULL,
    reference   VARCHAR(120),
    description TEXT,
    posted_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ledger_entries_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_ledger_entries_user_id
    ON ledger_entries (user_id);

CREATE INDEX idx_ledger_entries_posted_at
    ON ledger_entries (posted_at);

CREATE INDEX idx_ledger_entries_type
    ON ledger_entries (entry_type);


-- ==========================================
-- DISTRIBUTIONS
-- ==========================================

CREATE TABLE distributions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,
    distributed_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_distributions_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_distributions_user_id
    ON distributions (user_id);


-- ==========================================
-- INVITATIONS
-- ==========================================

CREATE TABLE invitations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number   VARCHAR(25)   NOT NULL,
    role           VARCHAR(40)   NOT NULL DEFAULT 'MEMBER',
    invited_by     UUID REFERENCES users (id),
    share_units    NUMERIC(19,4) NOT NULL CHECK (share_units > 0),
    price_per_unit NUMERIC(19,4) NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invitations_phone_number
    ON invitations (phone_number);

CREATE INDEX idx_invitations_status
    ON invitations (status);


-- ==========================================
-- STOKVEL CONFIG
-- ==========================================

CREATE TABLE stokvel_config (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    total_shares NUMERIC(19,4) NOT NULL DEFAULT 240,
    share_price  NUMERIC(19,2) NOT NULL DEFAULT 5000.00,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);


-- ==========================================
-- BORROWING CONFIG
-- ==========================================

CREATE TABLE borrowing_config (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interest_rate NUMERIC(5,2) NOT NULL DEFAULT 20.00,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


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
