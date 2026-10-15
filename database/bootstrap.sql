-- ============================================================================
-- TripMate — database bootstrap
-- ============================================================================
--
-- Creates the `tripmate` role and database. Run ONCE, as a PostgreSQL
-- superuser:
--
--   sudo -u postgres psql -v ON_ERROR_STOP=1 -f database/bootstrap.sql
--
-- To use a different password than the development default:
--
--   sudo -u postgres psql -v ON_ERROR_STOP=1 \
--        -v tripmate_password='something-else' -f database/bootstrap.sql
--
-- Safe to re-run: the role is only created if absent, and its password is
-- re-applied each time so it always matches what the loader uses.
--
-- ----------------------------------------------------------------------------
-- Why a dedicated role instead of the postgres superuser
-- ----------------------------------------------------------------------------
-- The application connects as `tripmate`, which owns only its own database and
-- holds no superuser rights. If the application or its credentials are ever
-- compromised, the blast radius is one database rather than the whole cluster.
-- The superuser is needed only for this one-time bootstrap.
-- ============================================================================

\set ON_ERROR_STOP on

-- Default the password, allowing -v tripmate_password=... to override it.
\if :{?tripmate_password}
\else
    \set tripmate_password 'tripmate_dev_pw'
\endif

\echo '==> Creating role "tripmate"'

-- CREATE ROLE cannot run inside a transaction block or a DO block, so the
-- statement is generated as text and executed with \gexec, which is also what
-- makes the existence check possible.
SELECT format('CREATE ROLE tripmate LOGIN PASSWORD %L', :'tripmate_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tripmate')
\gexec

-- Re-apply the password so a re-run converges on the documented credential.
SELECT format('ALTER ROLE tripmate WITH LOGIN PASSWORD %L', :'tripmate_password')
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tripmate')
\gexec

\echo '==> Creating database "tripmate"'

SELECT 'CREATE DATABASE tripmate OWNER tripmate ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'tripmate')
\gexec

-- ----------------------------------------------------------------------------
-- Schema privileges
-- ----------------------------------------------------------------------------
-- PostgreSQL 15 changed the default: the public schema no longer grants CREATE
-- to PUBLIC. The database owner has rights through the pg_database_owner role,
-- but granting explicitly keeps this working on older servers too and makes the
-- intent visible.
\connect tripmate

GRANT ALL ON SCHEMA public TO tripmate;

\echo '==> Done. Load the schema and sample data with: ./database/load.sh'
