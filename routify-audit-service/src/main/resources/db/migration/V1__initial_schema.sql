-- V1: Audit service initial schema (combined)
-- Includes: audit log, request log with full telemetry, replay tracking, and payload columns.

CREATE SCHEMA IF NOT EXISTS routify_audit;

-- Audit Log
-- Immutable event log. Every domain event is recorded here.
-- Partitioned by month for efficient time-based queries.

CREATE TABLE IF NOT EXISTS routify_audit.audit_log (
    event_id        UUID         NOT NULL,
    tenant_id       UUID,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(36),
    actor_id        VARCHAR(36),
    payload         JSONB        NOT NULL,
    correlation_id  VARCHAR(36),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE IF NOT EXISTS routify_audit.audit_log_y2026m01
    PARTITION OF routify_audit.audit_log
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE IF NOT EXISTS routify_audit.audit_log_y2026m02
    PARTITION OF routify_audit.audit_log
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE IF NOT EXISTS routify_audit.audit_log_y2026m03
    PARTITION OF routify_audit.audit_log
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE IF NOT EXISTS routify_audit.audit_log_y2026m04
    PARTITION OF routify_audit.audit_log
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE IF NOT EXISTS routify_audit.audit_log_y2026m05
    PARTITION OF routify_audit.audit_log
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS routify_audit.audit_log_y2026m06
    PARTITION OF routify_audit.audit_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE INDEX idx_audit_tenant_time  ON routify_audit.audit_log (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_event_type   ON routify_audit.audit_log (event_type, occurred_at DESC);
CREATE INDEX idx_audit_aggregate    ON routify_audit.audit_log (aggregate_type, aggregate_id);
CREATE INDEX idx_audit_correlation  ON routify_audit.audit_log (correlation_id);

-- Request Log
-- Per-request traffic analytics. High volume, shorter retention (30 days).
-- Also partitioned by month.

CREATE TABLE IF NOT EXISTS routify_audit.request_log (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            UUID,
    route_id             UUID,
    route_name           VARCHAR(255),
    correlation_id       VARCHAR(36),
    http_method          VARCHAR(10),
    path                 VARCHAR(500),
    upstream_uri         VARCHAR(500),
    response_status      INTEGER,
    duration_ms          BIGINT,
    request_size_bytes   BIGINT,
    response_size_bytes  BIGINT,
    client_ip            VARCHAR(45),
    user_id              VARCHAR(36),
    error_message        VARCHAR(1000),
    filter_trace         JSONB,
    -- Full telemetry capture
    query_string         VARCHAR(2000),
    request_headers      JSONB,
    response_headers     JSONB,
    failed               BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Replay tracking
    replay_status        VARCHAR(20),
    replayed_at          TIMESTAMPTZ,
    replay_count         INTEGER      NOT NULL DEFAULT 0,
    replay_response_status INTEGER,
    replay_error         VARCHAR(1000),
    -- Request/response body payload columns
    request_body         TEXT,
    response_body        TEXT,
    requested_at         TIMESTAMPTZ  NOT NULL,
    recorded_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_request_log PRIMARY KEY (id, requested_at)
) PARTITION BY RANGE (requested_at);

CREATE TABLE IF NOT EXISTS routify_audit.request_log_y2026m01
    PARTITION OF routify_audit.request_log
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE IF NOT EXISTS routify_audit.request_log_y2026m02
    PARTITION OF routify_audit.request_log
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE IF NOT EXISTS routify_audit.request_log_y2026m03
    PARTITION OF routify_audit.request_log
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE IF NOT EXISTS routify_audit.request_log_y2026m04
    PARTITION OF routify_audit.request_log
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE IF NOT EXISTS routify_audit.request_log_y2026m05
    PARTITION OF routify_audit.request_log
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS routify_audit.request_log_y2026m06
    PARTITION OF routify_audit.request_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE INDEX idx_req_tenant_time  ON routify_audit.request_log (tenant_id, requested_at DESC);
CREATE INDEX idx_req_route        ON routify_audit.request_log (route_id, requested_at DESC);
CREATE INDEX idx_req_status       ON routify_audit.request_log (response_status, requested_at DESC);
CREATE INDEX idx_req_correlation  ON routify_audit.request_log (correlation_id);

CREATE INDEX idx_req_failed
    ON routify_audit.request_log (failed, requested_at DESC)
    WHERE failed = TRUE;

CREATE INDEX idx_req_replay_pending
    ON routify_audit.request_log (replay_status, requested_at DESC)
    WHERE replay_status IN ('PENDING', 'FAILED');

COMMENT ON TABLE routify_audit.audit_log   IS 'Immutable domain event audit trail, partitioned by month';
COMMENT ON TABLE routify_audit.request_log IS 'Per-request traffic analytics, partitioned by month';

COMMENT ON COLUMN routify_audit.request_log.failed           IS 'true when the request ended with 5xx or unhandled exception, eligible for replay';
COMMENT ON COLUMN routify_audit.request_log.replay_status    IS 'Replay lifecycle: NULL | PENDING | IN_PROGRESS | SUCCEEDED | FAILED | SKIPPED';
COMMENT ON COLUMN routify_audit.request_log.replay_count     IS 'Number of replay attempts made';
COMMENT ON COLUMN routify_audit.request_log.request_headers  IS 'Sanitised request headers captured at gateway (sensitive headers redacted)';
COMMENT ON COLUMN routify_audit.request_log.response_headers IS 'Sanitised response headers captured at gateway';
COMMENT ON COLUMN routify_audit.request_log.request_body     IS 'Captured request body payload, only present when REQUEST_LOGGER logRequestBody=true';
COMMENT ON COLUMN routify_audit.request_log.response_body    IS 'Captured response body payload, only present when REQUEST_LOGGER logResponseBody=true';

