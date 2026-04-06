-- V3: AI Filter Decision audit table
-- Stores every AI filter evaluation decision published by routify-ai-service.
-- Consumed by admin-api for per-route analytics and compliance review.

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    evaluation_id    VARCHAR(36)  NOT NULL,
    route_id         UUID,
    route_name       VARCHAR(255),
    tenant_id        UUID,
    action           VARCHAR(10)  NOT NULL CHECK (action IN ('ALLOW', 'BLOCK', 'FLAG')),
    reason           TEXT,
    confidence       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    cached           BOOLEAN      NOT NULL DEFAULT FALSE,
    evaluation_mode  VARCHAR(10)  NOT NULL DEFAULT 'SYNC',
    latency_ms       BIGINT,
    method           VARCHAR(10),
    path             VARCHAR(500),
    client_ip        VARCHAR(45),
    evaluated_at     TIMESTAMPTZ  NOT NULL,
    recorded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_filter_decision PRIMARY KEY (id, evaluated_at)
) PARTITION BY RANGE (evaluated_at);

-- Monthly partitions for April 2026 through March 2027
CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m04
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m05
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m06
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m07
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m08
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m09
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m10
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m11
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2026m12
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2027m01
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2027m02
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');

CREATE TABLE IF NOT EXISTS routify_audit.ai_filter_decision_y2027m03
    PARTITION OF routify_audit.ai_filter_decision
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');

-- Indexes for common query patterns
CREATE INDEX idx_ai_decision_tenant_time
    ON routify_audit.ai_filter_decision (tenant_id, evaluated_at DESC);

CREATE INDEX idx_ai_decision_route_time
    ON routify_audit.ai_filter_decision (route_id, evaluated_at DESC);

CREATE INDEX idx_ai_decision_action
    ON routify_audit.ai_filter_decision (action, evaluated_at DESC);

CREATE INDEX idx_ai_decision_eval_id
    ON routify_audit.ai_filter_decision (evaluation_id);

-- Partial index for quick BLOCK count per route (most common analytics query)
CREATE INDEX idx_ai_decision_blocks
    ON routify_audit.ai_filter_decision (route_id, evaluated_at DESC)
    WHERE action = 'BLOCK';

COMMENT ON TABLE routify_audit.ai_filter_decision
    IS 'AI filter evaluation decisions — immutable audit trail, partitioned by month. Consumed from Kafka topic routify.ai.filter.decisions.';

COMMENT ON COLUMN routify_audit.ai_filter_decision.action
    IS 'LLM enforcement decision: ALLOW | BLOCK | FLAG';
COMMENT ON COLUMN routify_audit.ai_filter_decision.confidence
    IS 'LLM confidence score 0.0–1.0; 0.0 for circuit-breaker fallback verdicts';
COMMENT ON COLUMN routify_audit.ai_filter_decision.cached
    IS 'true when verdict was served from Redis verdict cache (no LLM call)';
COMMENT ON COLUMN routify_audit.ai_filter_decision.latency_ms
    IS 'Total evaluation latency in ms including cache lookup; excludes Redis write';

