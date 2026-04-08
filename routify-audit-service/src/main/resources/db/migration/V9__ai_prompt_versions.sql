-- V9: AI Prompt Versioning — stores prompt version history for AI filter policies.
-- Each filter can have multiple prompt versions (DRAFT, ACTIVE, ARCHIVED).
-- Only one ACTIVE version per filter at any time.
-- Owned by audit-service (analytics/compliance data).

CREATE TABLE IF NOT EXISTS routify_audit.ai_prompt_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filter_id       UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    version         INT NOT NULL,
    prompt_text     TEXT NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | ACTIVE | ARCHIVED
    accuracy_score  DECIMAL(5,2),   -- computed, NULL until enough labels
    total_decisions INT NOT NULL DEFAULT 0,
    correct_count   INT NOT NULL DEFAULT 0,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at    TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ,
    UNIQUE (filter_id, version)
);

CREATE INDEX idx_prompt_version_filter
    ON routify_audit.ai_prompt_version(filter_id, status);

CREATE INDEX idx_prompt_version_tenant
    ON routify_audit.ai_prompt_version(tenant_id);

COMMENT ON TABLE routify_audit.ai_prompt_version
    IS 'AI filter prompt version history — tracks DRAFT/ACTIVE/ARCHIVED lifecycle per filter.';
COMMENT ON COLUMN routify_audit.ai_prompt_version.status
    IS 'Lifecycle status: DRAFT → ACTIVE ↔ ARCHIVED. Only one ACTIVE per filter.';
COMMENT ON COLUMN routify_audit.ai_prompt_version.accuracy_score
    IS 'Computed accuracy: correct_count / total_decisions * 100. NULL until operator labels exist.';

