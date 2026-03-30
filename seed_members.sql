-- ============================================================
-- Vault Vibes — member seed data
-- Run AFTER all Flyway migrations (V1, V2, V3) have been applied.
-- Safe to re-run: idempotent guards on every section.
-- ============================================================


-- ============================================================
-- STOKVEL CONFIG — fix monthly contribution and cycle start date
-- ============================================================
UPDATE stokvel_config
SET monthly_contribution = 1000.00,
    cycle_start_date     = '2024-01-01'
WHERE stokvel_id = 'a0000000-0000-0000-0000-000000000001';


-- ============================================================
-- USERS
-- ============================================================
INSERT INTO users (id, phone_number, full_name, role, status, stokvel_id)
VALUES
  (gen_random_uuid(), '+27829754961', 'Sifumene Msane',    'TREASURER',   'PENDING', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27780785043', 'Sibusiso Mashita',  'CHAIRPERSON', 'ACTIVE', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27787531321', 'Mduduzi Ndlovu',    'MEMBER',      'PENDING', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27762935587', 'Siboniso Ngobese',  'MEMBER',      'PENDING', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27621841179', 'Muzi Hlengwa',      'MEMBER',      'PENDING', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27662750812', 'Thabsile Halimane', 'MEMBER',      'PENDING', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27638965240', 'Thinalwana',        'MEMBER',      'PENDING', 'a0000000-0000-0000-0000-000000000001'),
  (gen_random_uuid(), '+27671559882', 'Yolisa Siluma',     'MEMBER',      'PENDING', 'a0000000-0000-0000-0000-000000000001')
ON CONFLICT (phone_number) DO NOTHING;

-- NOTE: No invitations seeded.
-- Use the invite API (POST /api/invitations) for each member — this creates their
-- Cognito user, generates a temp password, and sends the WinSMS.
-- On first login, linkCognitoAccount() fires and sets their status → ACTIVE.


-- ============================================================
-- SHARES
-- ============================================================
WITH member_shares(phone, shares) AS (
  VALUES
    ('+27829754961', 2.0),
    ('+27780785043', 3.0),
    ('+27787531321', 2.0),
    ('+27762935587', 2.0),
    ('+27621841179', 1.0),
    ('+27662750812', 1.0),
    ('+27638965240', 1.0),
    ('+27671559882', 1.5)
)
INSERT INTO shares (user_id, share_units, price_per_unit)
SELECT u.id, m.shares, 1000
FROM member_shares m
JOIN users u ON u.phone_number = m.phone
WHERE m.shares > 0
  AND NOT EXISTS (SELECT 1 FROM shares s WHERE s.user_id = u.id);


-- ============================================================
-- CONTRIBUTIONS
-- Jan + Feb: all members
-- Mar: Sifu, Sibusiso, Siboniso, Thabsile, Yolisa, Thina
-- ============================================================

-- January 2024
INSERT INTO contributions (user_id, amount, contribution_date, verification_status)
SELECT s.user_id, s.share_units * 1000, DATE '2024-01-31', 'VERIFIED'
FROM shares s
JOIN users u ON u.id = s.user_id
ON CONFLICT DO NOTHING;

-- February 2024
INSERT INTO contributions (user_id, amount, contribution_date, verification_status)
SELECT s.user_id, s.share_units * 1000, DATE '2024-02-29', 'VERIFIED'
FROM shares s
JOIN users u ON u.id = s.user_id
ON CONFLICT DO NOTHING;

-- March 2024
INSERT INTO contributions (user_id, amount, contribution_date, verification_status)
SELECT s.user_id, s.share_units * 1000, DATE '2024-03-31', 'VERIFIED'
FROM shares s
JOIN users u ON u.id = s.user_id
WHERE u.phone_number IN (
  '+27829754961',  -- Sifu
  '+27780785043',  -- Sibusiso
  '+27762935587',  -- Siboniso
  '+27662750812',  -- Thabsile
  '+27671559882',  -- Yolisa
  '+27638965240'   -- Thina
)
ON CONFLICT DO NOTHING;


-- ============================================================
-- LOANS
-- ============================================================

-- Feb loans — both repaid
INSERT INTO loans (user_id, principal_amount, interest_rate, status, issued_at, due_at, amount_repaid)
SELECT u.id, l.principal, 20, 'REPAID', l.issued_at, l.issued_at + INTERVAL '15 days', l.principal * 1.20
FROM (VALUES
  ('+27621841179', 500::numeric, TIMESTAMP '2024-02-08'),  -- Muzi
  ('+27762935587', 300::numeric, TIMESTAMP '2024-02-14')   -- Siboniso
) AS l(phone, principal, issued_at)
JOIN users u ON u.phone_number = l.phone
WHERE NOT EXISTS (
  SELECT 1 FROM loans x
  WHERE x.user_id = u.id AND x.issued_at = l.issued_at
);

-- Muzi — active Mar loan (still outstanding)
INSERT INTO loans (user_id, principal_amount, interest_rate, status, issued_at, due_at, amount_repaid)
SELECT u.id, 1000, 20, 'ACTIVE', TIMESTAMP '2024-03-06', TIMESTAMP '2024-03-31', 0
FROM users u
WHERE u.phone_number = '+27621841179'
  AND NOT EXISTS (
    SELECT 1 FROM loans x
    WHERE x.user_id = u.id AND x.issued_at = TIMESTAMP '2024-03-06'
  );

-- Siboniso — Mar loan, now repaid
INSERT INTO loans (user_id, principal_amount, interest_rate, status, issued_at, due_at, amount_repaid)
SELECT u.id, 300, 20, 'REPAID', TIMESTAMP '2024-03-11', TIMESTAMP '2024-04-11', 360
FROM users u
WHERE u.phone_number = '+27762935587'
  AND NOT EXISTS (
    SELECT 1 FROM loans x
    WHERE x.user_id = u.id AND x.issued_at = TIMESTAMP '2024-03-11'
  );


-- ============================================================
-- LEDGER ENTRIES: CONTRIBUTIONS
-- ============================================================
INSERT INTO ledger_entries (user_id, entry_type, entry_scope, amount, description, posted_at)
SELECT c.user_id, 'CONTRIBUTION', 'USER', c.amount, 'Monthly contribution', c.contribution_date::timestamptz
FROM contributions c
WHERE NOT EXISTS (
  SELECT 1 FROM ledger_entries le
  WHERE le.user_id = c.user_id
    AND le.entry_type = 'CONTRIBUTION'
    AND le.posted_at::date = c.contribution_date
);


-- ============================================================
-- LEDGER ENTRIES: LOANS
-- ============================================================
WITH loan_ledger(phone, entry_type, amount, description, posted) AS (
  VALUES
    ('+27621841179', 'LOAN_ISSUED',     -500::numeric,  'Loan issued — Muzi Hlengwa',     TIMESTAMP '2024-02-08'),
    ('+27621841179', 'LOAN_REPAYMENT',   600::numeric,  'Loan repayment — Muzi Hlengwa',  TIMESTAMP '2024-02-20'),
    ('+27762935587', 'LOAN_ISSUED',     -300::numeric,  'Loan issued — Siboniso Ngobese', TIMESTAMP '2024-02-14'),
    ('+27762935587', 'LOAN_REPAYMENT',   360::numeric,  'Loan repayment — Siboniso Ngobese', TIMESTAMP '2024-02-25'),
    ('+27621841179', 'LOAN_ISSUED',    -1000::numeric,  'Loan issued — Muzi Hlengwa',     TIMESTAMP '2024-03-06'),
    ('+27762935587', 'LOAN_ISSUED',     -300::numeric,  'Loan issued — Siboniso Ngobese', TIMESTAMP '2024-03-11'),
    ('+27762935587', 'LOAN_REPAYMENT',   360::numeric,  'Loan repayment — Siboniso Ngobese', TIMESTAMP '2024-04-17')
)
INSERT INTO ledger_entries (user_id, entry_type, entry_scope, amount, description, posted_at)
SELECT u.id, l.entry_type, 'USER', l.amount, l.description, l.posted
FROM loan_ledger l
JOIN users u ON u.phone_number = l.phone
WHERE NOT EXISTS (
  SELECT 1 FROM ledger_entries le
  WHERE le.user_id = u.id
    AND le.entry_type = l.entry_type
    AND le.posted_at = l.posted
);


-- ============================================================
-- LEDGER ENTRIES: BANK INTEREST
-- ============================================================
INSERT INTO ledger_entries (user_id, entry_type, entry_scope, stokvel_id, amount, description, posted_at)
SELECT NULL, v.entry_type, 'SYSTEM', 'a0000000-0000-0000-0000-000000000001', v.amount, v.description, v.posted
FROM (VALUES
  ('BANK_INTEREST', 341.36::numeric, 'Bank interest received from prior year balance', TIMESTAMP '2024-01-01'),
  ('BANK_INTEREST',   6.72::numeric, 'Bank interest received',                         TIMESTAMP '2024-01-27'),
  ('BANK_INTEREST',  61.03::numeric, 'Bank interest received',                         TIMESTAMP '2024-02-27'),
  ('BANK_INTEREST', 109.37::numeric, 'Bank interest received',                         TIMESTAMP '2024-03-27')
) AS v(entry_type, amount, description, posted)
WHERE NOT EXISTS (
  SELECT 1 FROM ledger_entries le
  WHERE le.entry_type = 'BANK_INTEREST'
    AND le.posted_at = v.posted
);
