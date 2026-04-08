-- Webhook Notification System — tenant-scoped webhook subscriptions and delivery log.

CREATE TABLE routify_identity.webhook_subscription (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    url             VARCHAR(2048) NOT NULL,
    secret          VARCHAR(255) NOT NULL,  -- HMAC-SHA256 signing key
    event_types     Text[] NOT NULL,         -- e.g., {'ROUTE_ACTIVATED','CERT_EXPIRING'}
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failure_count   INT NOT NULL DEFAULT 0,
    last_delivered_at TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE routify_identity.webhook_delivery (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID NOT NULL REFERENCES routify_identity.webhook_subscription(id),
    event_type          VARCHAR(100) NOT NULL,
    payload             JSONB NOT NULL,
    response_status     INT,
    response_body       TEXT,
    attempt             INT NOT NULL DEFAULT 1,
    status              VARCHAR(20) NOT NULL,  -- PENDING, DELIVERED, FAILED
    delivered_at        TIMESTAMPTZ,
    next_retry_at       TIMESTAMPTZ,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_sub_tenant ON routify_identity.webhook_subscription(tenant_id, status);
CREATE INDEX idx_webhook_delivery_sub ON routify_identity.webhook_delivery(subscription_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_retry ON routify_identity.webhook_delivery(status, next_retry_at)
    WHERE status = 'PENDING';

