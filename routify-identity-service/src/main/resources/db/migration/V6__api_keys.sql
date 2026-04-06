-- API Key lifecycle management — durable storage for API keys
-- managed through the dashboard and projected to Redis for gateway reads.

CREATE TABLE routify_identity.api_key (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    user_id         UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    key_hash        VARCHAR(512) NOT NULL UNIQUE,   -- SHA-256 of raw key
    key_prefix      VARCHAR(12) NOT NULL,            -- first 8 chars for display ("rtfy_a1b2...")
    role            VARCHAR(50) NOT NULL DEFAULT 'OPERATOR',
    email           VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | REVOKED | EXPIRED
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT fk_apikey_tenant FOREIGN KEY (tenant_id)
        REFERENCES routify_identity.tenant(id)
);

CREATE INDEX idx_apikey_tenant ON routify_identity.api_key(tenant_id, status);
CREATE INDEX idx_apikey_hash   ON routify_identity.api_key(key_hash);

