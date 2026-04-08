-- ============================================================================
-- V5: ACME (Automated Certificate Management Environment) support tables
-- ============================================================================

CREATE TABLE routify_cert.acme_account (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    email           VARCHAR(255) NOT NULL,
    account_url     VARCHAR(2048),       -- ACME account URL after registration
    key_pair_pem    TEXT NOT NULL,        -- AES-encrypted account key pair
    key_pair_iv     VARCHAR(64) NOT NULL, -- AES-GCM initialization vector
    key_pair_tag    VARCHAR(64) NOT NULL, -- AES-GCM authentication tag
    provider        VARCHAR(100) NOT NULL DEFAULT 'LETSENCRYPT',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE routify_cert.acme_order (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES routify_cert.acme_account(id),
    tenant_id       UUID NOT NULL,
    domain          VARCHAR(255) NOT NULL,
    cert_group_id   UUID,                -- target cert group for issued cert
    challenge_type  VARCHAR(20) NOT NULL DEFAULT 'HTTP_01',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    order_url       VARCHAR(2048),
    challenge_token  VARCHAR(1024),
    challenge_content TEXT,
    cert_id         UUID,                -- resulting certificate ID after issuance
    auto_renew      BOOLEAN NOT NULL DEFAULT true,
    last_renewed_at TIMESTAMPTZ,
    next_renewal_at TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_acme_order_renewal ON routify_cert.acme_order(auto_renew, next_renewal_at)
    WHERE status IN ('COMPLETED', 'PENDING');

