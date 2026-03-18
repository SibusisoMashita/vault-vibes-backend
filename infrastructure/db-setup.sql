-- ─────────────────────────────────────────────────────────────────────────────
-- DEV Database Setup
-- Run as the postgres superuser against the prod-omnisolve-postgres RDS instance
-- This creates an isolated vaultvibes_dev database with a dedicated user.
-- The dev user has NO access to the production vaultvibes database.
-- ─────────────────────────────────────────────────────────────────────────────

-- Step 1: Create the dev user
-- Replace <secure_password> with a strong random password.
-- Store the password in: vaultvibes/dev/backend/db (Secrets Manager)

CREATE USER vaultvibes_dev_app WITH PASSWORD '<secure_password>';

-- Step 2: Create the dev database owned by the dev user
CREATE DATABASE vaultvibes_dev
  OWNER vaultvibes_dev_app
  ENCODING 'UTF8'
  LC_COLLATE 'en_US.UTF-8'
  LC_CTYPE 'en_US.UTF-8'
  TEMPLATE template0;

-- Step 3: Ensure dev user cannot access prod database
-- (no explicit GRANT means no access by default in Postgres)
-- Revoke any accidental public access
REVOKE ALL ON DATABASE vaultvibes FROM vaultvibes_dev_app;

-- Step 4: Grant all on dev database
GRANT ALL PRIVILEGES ON DATABASE vaultvibes_dev TO vaultvibes_dev_app;

-- ─── Connect to vaultvibes_dev before running steps 5-6 ───────────────────
-- \c vaultvibes_dev

-- Step 5: Grant schema usage (run after connecting to vaultvibes_dev)
GRANT ALL ON SCHEMA public TO vaultvibes_dev_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO vaultvibes_dev_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO vaultvibes_dev_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT ALL ON TABLES TO vaultvibes_dev_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT ALL ON SEQUENCES TO vaultvibes_dev_app;

-- ─── Verification ─────────────────────────────────────────────────────────
-- Confirm dev user cannot see prod tables:
-- REVOKE ALL ON DATABASE vaultvibes FROM vaultvibes_dev_app;  -- already done above
-- SELECT has_database_privilege('vaultvibes_dev_app', 'vaultvibes', 'CONNECT');
-- Expected: f (false)

-- ─── After running this script ────────────────────────────────────────────
-- Update the DEV Secrets Manager secret with the actual password:
--
-- aws secretsmanager put-secret-value \
--   --secret-id vaultvibes/dev/backend/db \
--   --secret-string '{
--     "DB_URL":     "jdbc:postgresql://prod-omnisolve-postgres.cmrg4wcome3h.us-east-1.rds.amazonaws.com:5432/vaultvibes_dev",
--     "DB_USERNAME":"vaultvibes_dev_app",
--     "DB_PASSWORD":"<secure_password>"
--   }'
--
-- Flyway will create all tables on first ECS task startup via Spring Boot.
