-- V1: Route service initial schema (combined)
-- Includes: filter definitions, routes, route filters, gateway config, outbox events.

CREATE SCHEMA IF NOT EXISTS routify;

-- Filter Definition
-- Reusable filter configurations (rate limit, JWT auth, transformers, etc.)

CREATE TABLE IF NOT EXISTS routify.filter_definition (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL,
    name               VARCHAR(255) NOT NULL,
    description        VARCHAR(1000),
    filter_type        VARCHAR(50)  NOT NULL,
    config             JSONB        NOT NULL DEFAULT '{}',
    gateway_config_ref JSONB,
    system_managed     BOOLEAN      NOT NULL DEFAULT false,
    enabled            BOOLEAN      NOT NULL DEFAULT true,
    usage_count        INTEGER      NOT NULL DEFAULT 0,
    created_by         VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    CONSTRAINT pk_filter_definition      PRIMARY KEY (id),
    CONSTRAINT uq_filter_name_tenant     UNIQUE (name, tenant_id),
    CONSTRAINT chk_filter_usage          CHECK (usage_count >= 0)
);

CREATE INDEX idx_filter_def_tenant    ON routify.filter_definition (tenant_id);
CREATE INDEX idx_filter_def_type      ON routify.filter_definition (filter_type, tenant_id);
CREATE INDEX idx_filter_def_config    ON routify.filter_definition USING gin (config);
CREATE INDEX idx_filter_def_enabled   ON routify.filter_definition (enabled, tenant_id) WHERE enabled = true;
CREATE INDEX idx_filter_def_gw_config_ref
    ON routify.filter_definition USING gin (gateway_config_ref)
    WHERE gateway_config_ref IS NOT NULL;

-- Route
-- Core routing table, each row is a route in the API gateway

CREATE TABLE IF NOT EXISTS routify.route (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    description    VARCHAR(1000),
    path_pattern   VARCHAR(500) NOT NULL,
    methods        VARCHAR(100) NOT NULL DEFAULT '*',
    upstream_uri   VARCHAR(500) NOT NULL,
    strip_prefix   VARCHAR(200),
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    version        INTEGER      NOT NULL DEFAULT 1,
    extra_config   JSONB        NOT NULL DEFAULT '{}',
    created_by     VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    activated_at   TIMESTAMPTZ,
    CONSTRAINT pk_route               PRIMARY KEY (id),
    CONSTRAINT uq_route_name_tenant   UNIQUE (name, tenant_id),
    CONSTRAINT chk_route_status       CHECK (status IN ('DRAFT','ACTIVE','DISABLED','ARCHIVED')),
    CONSTRAINT chk_route_version      CHECK (version >= 1)
);

CREATE INDEX idx_route_tenant         ON routify.route (tenant_id);
CREATE INDEX idx_route_status         ON routify.route (status, tenant_id);
CREATE INDEX idx_route_active         ON routify.route (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_route_path           ON routify.route (path_pattern, methods);
CREATE INDEX idx_route_extra_config   ON routify.route USING gin (extra_config);

-- Enforce uniqueness of (tenant_id, path_pattern, methods) for ACTIVE routes
CREATE UNIQUE INDEX uq_route_path_methods_tenant_active
    ON routify.route (tenant_id, path_pattern, methods)
    WHERE status = 'ACTIVE';

-- Route Filter (join table)
-- Associates filters with routes in execution order

CREATE TABLE IF NOT EXISTS routify.route_filter (
    id                   UUID    NOT NULL DEFAULT gen_random_uuid(),
    route_id             UUID    NOT NULL,
    filter_definition_id UUID    NOT NULL,
    filter_order         INTEGER NOT NULL DEFAULT 0,
    phase                VARCHAR(10) NOT NULL DEFAULT 'PRE',
    enabled              BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT pk_route_filter          PRIMARY KEY (id),
    CONSTRAINT uq_route_filter          UNIQUE (route_id, filter_definition_id),
    CONSTRAINT fk_route_filter_route    FOREIGN KEY (route_id)
        REFERENCES routify.route (id) ON DELETE CASCADE,
    CONSTRAINT fk_route_filter_filter   FOREIGN KEY (filter_definition_id)
        REFERENCES routify.filter_definition (id) ON DELETE RESTRICT,
    CONSTRAINT chk_route_filter_phase   CHECK (phase IN ('PRE','POST')),
    CONSTRAINT chk_route_filter_order   CHECK (filter_order >= 0)
);

CREATE INDEX idx_route_filter_route   ON routify.route_filter (route_id);
CREATE INDEX idx_route_filter_filter  ON routify.route_filter (filter_definition_id);
CREATE INDEX idx_route_filter_order   ON routify.route_filter (route_id, filter_order);

-- Gateway Configuration
-- Stores the full gateway config as JSONB, versioned and tenant-scoped.

CREATE TABLE IF NOT EXISTS routify.gateway_config (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    config_key   VARCHAR(100) NOT NULL,
    tenant_id    UUID,
    config_value JSONB        NOT NULL DEFAULT '{}',
    version      BIGINT       NOT NULL DEFAULT 1,
    updated_by   VARCHAR(255),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_gateway_config        PRIMARY KEY (id),
    CONSTRAINT uq_gateway_config_key    UNIQUE (config_key, tenant_id)
);

CREATE INDEX idx_gateway_config_key     ON routify.gateway_config (config_key);
CREATE INDEX idx_gateway_config_tenant  ON routify.gateway_config (tenant_id) WHERE tenant_id IS NOT NULL;

-- Seed the default platform-wide config
INSERT INTO routify.gateway_config (config_key, tenant_id, config_value, updated_by)
VALUES ('global', NULL, '{}'::jsonb, 'system')
ON CONFLICT (config_key, tenant_id) DO NOTHING;

-- Outbox Events
-- Transactional outbox for reliable Kafka event publishing

CREATE TABLE IF NOT EXISTS routify.outbox_event (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(36)  NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    partition_key  VARCHAR(36),
    payload        JSONB        NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_event         PRIMARY KEY (id),
    CONSTRAINT chk_outbox_status       CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    CONSTRAINT chk_outbox_retry        CHECK (retry_count >= 0)
);

CREATE INDEX idx_outbox_status_created    ON routify.outbox_event (status, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_failed_retry      ON routify.outbox_event (retry_count, created_at)
    WHERE status = 'FAILED';
CREATE INDEX idx_outbox_aggregate         ON routify.outbox_event (aggregate_type, aggregate_id);

COMMENT ON TABLE routify.route             IS 'Gateway routes, each row is a live or draft route configuration';
COMMENT ON TABLE routify.filter_definition IS 'Reusable filter definitions: rate limit, auth, transform, etc.';
COMMENT ON TABLE routify.route_filter      IS 'Ordered filter chain for each route';
COMMENT ON TABLE routify.gateway_config    IS 'Durable gateway configuration, survives Redis flushes, restarts, and rollouts.';
COMMENT ON TABLE routify.outbox_event      IS 'Transactional outbox for reliable Kafka event publishing';

