-- V4__ai_modifier_decision_table.sql
-- Creates the AI Modification Filter decision audit table.
-- PII-safe: original and mutated bodies are never stored — only SHA-256 hashes.

CREATE TABLE IF NOT EXISTS routify_audit.ai_modifier_decision (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    mutation_id         TEXT,
    route_id            UUID,
    route_name          TEXT,
    tenant_id           UUID,
    mutation_applied    BOOLEAN     NOT NULL,
    mutation_type       VARCHAR(30),
    reason              TEXT,
    original_body_hash  VARCHAR(64),
    mutated_body_hash   VARCHAR(64),
    headers_modified    JSONB,
    cached              BOOLEAN     NOT NULL DEFAULT FALSE,
    latency_ms          BIGINT,
    method              VARCHAR(10),
    path                VARCHAR(500),
    client_ip           VARCHAR(45),
    evaluated_at        TIMESTAMPTZ NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_ai_mod_tenant_time
    ON routify_audit.ai_modifier_decision (tenant_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_mod_route_time
    ON routify_audit.ai_modifier_decision (route_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_mod_mutation_id
    ON routify_audit.ai_modifier_decision (mutation_id);

CREATE INDEX IF NOT EXISTS idx_ai_mod_type
    ON routify_audit.ai_modifier_decision (mutation_type, evaluated_at DESC);

COMMENT ON TABLE routify_audit.ai_modifier_decision IS
    'Immutable audit records for AI Modification Filter decisions. '
    'Retains mutation type, reason, and body hashes (never raw bodies) for compliance.';

