-- V2: Add Transactional Outbox to identity-service
-- Eliminates the dual-write anti-pattern in TenantService / UserService.
-- Events are now written atomically in the same DB transaction as the entity mutation
-- and published to Kafka by IdentityOutboxPoller — mirroring route-service and cert-vault.

CREATE TABLE IF NOT EXISTS routify_identity.outbox_event (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
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
    CONSTRAINT pk_identity_outbox_event       PRIMARY KEY (id),
    CONSTRAINT chk_identity_outbox_status     CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_identity_outbox_retry      CHECK (retry_count >= 0)
);

-- Outbox poller queries: fetch PENDING, retry FAILED below max-retry threshold.
CREATE INDEX idx_identity_outbox_pending
    ON routify_identity.outbox_event (status, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_identity_outbox_failed
    ON routify_identity.outbox_event (retry_count, created_at)
    WHERE status = 'FAILED';

CREATE INDEX idx_identity_outbox_aggregate
    ON routify_identity.outbox_event (aggregate_type, aggregate_id);

-- Also add FK from refresh_token.tenant_id → tenant.id for referential integrity.
-- Orphaned refresh tokens are cleaned up when a tenant is hard-deleted.
-- ON DELETE CASCADE matches the user→token cascade already defined in V1.
ALTER TABLE routify_identity.refresh_token
    ADD CONSTRAINT fk_refresh_token_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES routify_identity.tenant (id)
        ON DELETE CASCADE;

COMMENT ON TABLE routify_identity.outbox_event IS
    'Transactional outbox for identity-service domain events (TenantCreated, UserCreated, etc.). '
    'Events are written atomically with the entity mutation and published to Kafka by IdentityOutboxPoller.';

COMMENT ON CONSTRAINT fk_refresh_token_tenant ON routify_identity.refresh_token IS
    'Enforces referential integrity between refresh tokens and their owning tenant. '
    'Added in V2 — previously tenant_id was an unguarded denormalised column.';

