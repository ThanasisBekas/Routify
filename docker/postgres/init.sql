-- Routify PostgreSQL initialisation script
-- Creates schemas owned by respective services.
-- Flyway migrations in each service will create all tables.
--
-- After all Flyway migrations have run, apply docker/postgres/ops_monitoring.sql
-- to deploy the cross-schema routify_ops monitoring views and procedures.

-- ─── Schemas ──────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS routify;           -- Route service (routes, filters, outbox)
CREATE SCHEMA IF NOT EXISTS routify_identity;  -- Identity service (users, tenants)
CREATE SCHEMA IF NOT EXISTS routify_audit;     -- Audit service (event log, request log)
CREATE SCHEMA IF NOT EXISTS routify_cert;      -- Certificate Vault (stored_certificate, cert_outbox_event)
CREATE SCHEMA IF NOT EXISTS routify_ops;       -- Cross-schema ops/monitoring views (see ops_monitoring.sql)

-- ─── Application role permissions ─────────────────────────────────────────────
GRANT ALL PRIVILEGES ON SCHEMA routify          TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_identity TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_audit    TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_cert     TO routify;
GRANT ALL PRIVILEGES ON SCHEMA routify_ops      TO routify;

-- ─── Monitoring role (read-only, used by Prometheus exporters & ops tooling) ──
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'routify_monitor') THEN
        CREATE ROLE routify_monitor NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA routify          TO routify_monitor;
GRANT USAGE ON SCHEMA routify_identity TO routify_monitor;
GRANT USAGE ON SCHEMA routify_audit    TO routify_monitor;
GRANT USAGE ON SCHEMA routify_cert     TO routify_monitor;
GRANT USAGE ON SCHEMA routify_ops      TO routify_monitor;

-- Default search path — includes all service schemas and the ops schema
ALTER ROLE routify         SET search_path TO routify, routify_identity, routify_audit, routify_cert, routify_ops, public;
ALTER ROLE routify_monitor SET search_path TO routify_ops, routify, routify_identity, routify_audit, routify_cert, public;

-- ─── Extensions ───────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";
