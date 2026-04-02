-- V1: Certificate Vault initial schema (combined)
-- Includes: stored certificates, certificate groups, and transactional outbox.

CREATE SCHEMA IF NOT EXISTS routify_cert;

-- Certificate Group
-- A group owns a stable logical ID that the gateway and filters reference.
-- Multiple certificates may belong to the same group (rotation, multi-chain, etc.).

CREATE TABLE IF NOT EXISTS routify_cert.cert_group (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    logical_id   VARCHAR(100) NOT NULL,
    alias        VARCHAR(255) NOT NULL,
    description  VARCHAR(1000),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_by   VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    CONSTRAINT pk_cert_group               PRIMARY KEY (id),
    CONSTRAINT uq_cert_group_logical_tenant UNIQUE (logical_id, tenant_id),
    CONSTRAINT uq_cert_group_alias_tenant   UNIQUE (alias, tenant_id),
    CONSTRAINT chk_cert_group_status        CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_cert_group_tenant      ON routify_cert.cert_group (tenant_id);
CREATE INDEX idx_cert_group_logical     ON routify_cert.cert_group (logical_id, tenant_id);
CREATE INDEX idx_cert_group_status      ON routify_cert.cert_group (status, tenant_id);

COMMENT ON TABLE routify_cert.cert_group IS
    'Logical group owning one or more certificates. The group logical_id is the stable key used by the gateway TLS registry.';

-- Stored Certificate
-- Each row represents one uploaded certificate (PEM chain or PKCS12 bundle).
-- Certificates are always assigned to a group at upload time.

CREATE TABLE IF NOT EXISTS routify_cert.stored_certificate (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL,
    logical_id       VARCHAR(100),
    alias            VARCHAR(255)  NOT NULL,
    description      VARCHAR(1000),
    format           VARCHAR(20)   NOT NULL DEFAULT 'PEM',
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    subject_dn       VARCHAR(500),
    issuer_dn        VARCHAR(500),
    serial_number    VARCHAR(100),
    not_before       TIMESTAMPTZ,
    not_after        TIMESTAMPTZ,
    signature_alg    VARCHAR(50),
    key_algorithm    VARCHAR(50),
    key_size         INTEGER,
    fingerprint_sha1 VARCHAR(64),
    fingerprint_sha256 VARCHAR(128),
    san_dns          TEXT[],
    san_ip           TEXT[],
    is_ca            BOOLEAN       NOT NULL DEFAULT false,
    cert_data_enc    TEXT          NOT NULL,
    private_key_enc  TEXT,
    enc_iv           VARCHAR(64)   NOT NULL,
    enc_tag          VARCHAR(64)   NOT NULL,
    gateway_tls_logical_id VARCHAR(100),
    group_id         UUID          REFERENCES routify_cert.cert_group(id) ON DELETE SET NULL,
    member_alias     VARCHAR(100),
    uploaded_by      VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    CONSTRAINT pk_stored_certificate       PRIMARY KEY (id),
    CONSTRAINT uq_cert_alias_tenant        UNIQUE (alias, tenant_id),
    CONSTRAINT chk_cert_format             CHECK (format IN ('PEM', 'PKCS12')),
    CONSTRAINT chk_cert_status             CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'DELETED'))
);

CREATE INDEX idx_cert_tenant          ON routify_cert.stored_certificate (tenant_id);
CREATE INDEX idx_cert_logical_id      ON routify_cert.stored_certificate (logical_id, tenant_id);
CREATE INDEX idx_cert_status          ON routify_cert.stored_certificate (status, tenant_id);
CREATE INDEX idx_cert_expires         ON routify_cert.stored_certificate (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_cert_active          ON routify_cert.stored_certificate (status, tenant_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_cert_gateway_ref     ON routify_cert.stored_certificate (gateway_tls_logical_id) WHERE gateway_tls_logical_id IS NOT NULL;
CREATE INDEX idx_cert_group_id        ON routify_cert.stored_certificate (group_id) WHERE group_id IS NOT NULL;
CREATE INDEX idx_cert_member_alias    ON routify_cert.stored_certificate (group_id, member_alias) WHERE group_id IS NOT NULL;

COMMENT ON TABLE routify_cert.stored_certificate IS
    'Encrypted certificate vault. Certificate material is AES-256-GCM encrypted at rest.';

COMMENT ON COLUMN routify_cert.stored_certificate.logical_id IS
    'Internal identifier, auto-generated as cert-<uuid> for new uploads. Retained for internal tracing.';

COMMENT ON COLUMN routify_cert.stored_certificate.gateway_tls_logical_id IS
    'Legacy direct gateway TLS binding, kept for backwards compatibility.';

COMMENT ON COLUMN routify_cert.stored_certificate.group_id IS
    'FK to cert_group. The gateway uses the group logical_id as the TLS registry key.';

COMMENT ON COLUMN routify_cert.stored_certificate.member_alias IS
    'Short label distinguishing this certificate within its group (e.g. primary, backup-2025).';

-- Certificate Outbox
-- Transactional outbox for reliable Kafka event publishing

CREATE TABLE IF NOT EXISTS routify_cert.cert_outbox_event (
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
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT pk_cert_outbox_event    PRIMARY KEY (id),
    CONSTRAINT chk_cert_outbox_status  CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_cert_outbox_pending  ON routify_cert.cert_outbox_event (status, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_cert_outbox_failed   ON routify_cert.cert_outbox_event (status, retry_count) WHERE status = 'FAILED';

COMMENT ON TABLE routify_cert.cert_outbox_event IS
    'Transactional outbox for cert-vault domain events published to Kafka.';

