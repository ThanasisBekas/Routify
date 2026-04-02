-- Routify PostgreSQL initialisation script
-- Creates schemas owned by respective services.
-- Flyway migrations in each service will create all tables.

-- ─── Schemas ──────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS routify;           -- Route service (routes, filters, outbox)
CREATE SCHEMA IF NOT EXISTS routify_identity;  -- Identity service (users, tenants)
CREATE SCHEMA IF NOT EXISTS routify_audit;     -- Audit service (event log, request log)
CREATE SCHEMA IF NOT EXISTS routify_cert;      -- Certificate Vault (stored_certificate, cert_outbox_event)

-- ─── Permissions ──────────────────────────────────────────────────────────────
GRANT ALL PRIVILEGES ON SCHEMA routify          TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_identity TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_audit    TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_cert     TO routify;

-- Default search path — includes all four service schemas
ALTER ROLE routify SET search_path TO routify, routify_identity, routify_audit, routify_cert, public;

-- ─── Extensions ───────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";
