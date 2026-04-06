-- V3: Quota Policy table — moves TenantPlan quota limits out of the Java enum
-- and into the database so they can be changed at runtime without a redeploy.
--
-- The TenantPlan enum in routify-common still exists as the authoritative TYPE,
-- but the LIMITS (maxRoutes, maxFilters, monthlyRequestQuota) are now driven by
-- this table. Services read the active policy row on startup and cache it.
--
-- When the platform team changes a plan's limits they UPDATE this table and
-- publish a GATEWAY_CONFIG_EVENTS event to invalidate caches.

CREATE TABLE IF NOT EXISTS routify_identity.quota_policy (
    plan                   VARCHAR(20)  NOT NULL,
    max_routes             INTEGER      NOT NULL CHECK (max_routes     >= 0),
    max_filters            INTEGER      NOT NULL CHECK (max_filters    >= 0),
    monthly_request_quota  BIGINT       NOT NULL CHECK (monthly_request_quota >= 0),
    updated_by             VARCHAR(255),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_quota_policy  PRIMARY KEY (plan),
    CONSTRAINT chk_quota_plan   CHECK (plan IN ('FREE', 'STARTER', 'PRO', 'ENTERPRISE'))
);

-- Seed the initial values matching the current TenantPlan enum constants.
-- These can be changed via UPDATE without a service redeploy.
INSERT INTO routify_identity.quota_policy (plan, max_routes, max_filters, monthly_request_quota, updated_by)
VALUES
    ('FREE',       10,              5,              1000,           'system'),
    ('STARTER',    50,              20,             10000,          'system'),
    ('PRO',        200,             100,            100000,         'system'),
    ('ENTERPRISE', 2147483647,      2147483647,     9223372036854775807, 'system')
ON CONFLICT (plan) DO NOTHING;

COMMENT ON TABLE routify_identity.quota_policy IS
    'Runtime-editable quota limits per TenantPlan. '
    'Replaces hard-coded values in the TenantPlan Java enum so limits can be changed '
    'without rebuilding and redeploying routify-common across all services.';

COMMENT ON COLUMN routify_identity.quota_policy.plan IS
    'Matches TenantPlan enum values: FREE | STARTER | PRO | ENTERPRISE';

COMMENT ON COLUMN routify_identity.quota_policy.monthly_request_quota IS
    'Monthly request allowance. ENTERPRISE uses Long.MAX_VALUE (effectively unlimited).';

