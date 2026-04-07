-- ─── V8: Tenant Usage Daily ─────────────────────────────────────────────────
-- Stores daily snapshots of tenant resource usage and request metrics.
-- Used by the usage analytics dashboard and trend charts.

CREATE TABLE routify_audit.tenant_usage_daily (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    date            DATE NOT NULL,
    route_count     INT NOT NULL DEFAULT 0,
    filter_count    INT NOT NULL DEFAULT 0,
    request_count   BIGINT NOT NULL DEFAULT 0,
    error_count     BIGINT NOT NULL DEFAULT 0,
    snapshot_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, date)
);

CREATE INDEX idx_tenant_usage_tenant_date ON routify_audit.tenant_usage_daily(tenant_id, date DESC);

