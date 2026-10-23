-- ============================================================================
-- TripMate — reset
-- ============================================================================
--
-- Drops every TripMate table, in reverse dependency order, leaving the
-- database empty and ready for schema.sql.
--
--   psql -f database/reset.sql
--
-- DESTRUCTIVE: all data in these tables is lost. Dropping a table also drops
-- its BIGSERIAL sequences and its indexes, so nothing is left behind.
--
-- Only the TripMate tables are touched. Anything else in the database
-- (including other schemas and roles) is left alone.
-- ============================================================================

DROP TABLE IF EXISTS past_trips            CASCADE;
DROP TABLE IF EXISTS google_calendar_events CASCADE;
DROP TABLE IF EXISTS google_calendar_links   CASCADE;
DROP TABLE IF EXISTS trip_calendar_feeds CASCADE;
DROP TABLE IF EXISTS trip_summary_emails CASCADE;
DROP TABLE IF EXISTS password_resets CASCADE;
DROP TABLE IF EXISTS email_verifications CASCADE;
DROP TABLE IF EXISTS expense_splits  CASCADE;
DROP TABLE IF EXISTS settlements     CASCADE;
DROP TABLE IF EXISTS expenses        CASCADE;
DROP TABLE IF EXISTS itinerary_items CASCADE;
DROP TABLE IF EXISTS trip_members    CASCADE;
DROP TABLE IF EXISTS trips           CASCADE;
DROP TABLE IF EXISTS users           CASCADE;
