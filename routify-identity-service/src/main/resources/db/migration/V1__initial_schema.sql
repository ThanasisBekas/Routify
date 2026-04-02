-- V1: Identity service initial schema (combined)
-- Includes: tenants, users (with must_change_password flag), and refresh token store.

CREATE SCHEMA IF NOT EXISTS routify_identity;

-- Tenant
CREATE TABLE IF NOT EXISTS routify_identity.tenant (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    name          VARCHAR(255) NOT NULL,
    slug          VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    plan          VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    contact_email VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tenant      PRIMARY KEY (id),
    CONSTRAINT uq_tenant_name UNIQUE (name),
    CONSTRAINT uq_tenant_slug UNIQUE (slug),
    CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    CONSTRAINT chk_tenant_plan   CHECK (plan   IN ('FREE','STARTER','PRO','ENTERPRISE'))
);

CREATE INDEX idx_tenant_slug   ON routify_identity.tenant (slug);
CREATE INDEX idx_tenant_status ON routify_identity.tenant (status);

-- App User
CREATE TABLE IF NOT EXISTS routify_identity.app_user (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id              UUID,
    username               VARCHAR(100) NOT NULL,
    email                  VARCHAR(255) NOT NULL,
    password_hash          VARCHAR(255) NOT NULL,
    role                   VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password   BOOLEAN      NOT NULL DEFAULT false,
    last_login_at          TIMESTAMPTZ,
    failed_login_attempts  INTEGER      NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    CONSTRAINT pk_app_user                   PRIMARY KEY (id),
    CONSTRAINT uq_user_email_tenant          UNIQUE (email, tenant_id),
    CONSTRAINT uq_user_username_tenant       UNIQUE (username, tenant_id),
    CONSTRAINT fk_user_tenant                FOREIGN KEY (tenant_id)
        REFERENCES routify_identity.tenant (id) ON DELETE CASCADE,
    CONSTRAINT chk_user_role   CHECK (role   IN ('SUPER_ADMIN','TENANT_ADMIN','VIEWER','OPERATOR')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE','LOCKED','DELETED'))
);

CREATE INDEX idx_user_tenant       ON routify_identity.app_user (tenant_id);
CREATE INDEX idx_user_username     ON routify_identity.app_user (username, tenant_id);
CREATE INDEX idx_user_email        ON routify_identity.app_user (email, tenant_id);
CREATE INDEX idx_user_status       ON routify_identity.app_user (status) WHERE status = 'ACTIVE';

-- Refresh Token Store
CREATE TABLE IF NOT EXISTS routify_identity.refresh_token (
    jti        VARCHAR(36)  NOT NULL,
    user_id    UUID         NOT NULL,
    tenant_id  UUID,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_refresh_token    PRIMARY KEY (jti),
    CONSTRAINT fk_refresh_user     FOREIGN KEY (user_id)
        REFERENCES routify_identity.app_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_user    ON routify_identity.refresh_token (user_id);
CREATE INDEX idx_refresh_expires ON routify_identity.refresh_token (expires_at) WHERE revoked = false;

COMMENT ON TABLE routify_identity.tenant        IS 'Platform tenants / organizations';
COMMENT ON TABLE routify_identity.app_user      IS 'Platform users belonging to tenants';
COMMENT ON TABLE routify_identity.refresh_token IS 'JWT refresh token revocation store';
COMMENT ON COLUMN routify_identity.app_user.must_change_password IS
    'When true the user must change their password before any other action is permitted.';

