-- V11: Platform Alerting Engine — alert_rule + alert_event tables

CREATE TABLE routify_audit.alert_rule (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         VARCHAR(1000),
    metric              VARCHAR(50) NOT NULL,
    route_id            UUID,
    operator            VARCHAR(10) NOT NULL,
    threshold           DECIMAL(12,4) NOT NULL,
    window_minutes      INT NOT NULL DEFAULT 5,
    cooldown_minutes    INT NOT NULL DEFAULT 30,
    severity            VARCHAR(20) NOT NULL DEFAULT 'WARNING',
    enabled             BOOLEAN NOT NULL DEFAULT true,
    current_state       VARCHAR(20) NOT NULL DEFAULT 'OK',
    state_changed_at    TIMESTAMPTZ,
    consecutive_breaches INT NOT NULL DEFAULT 0,
    last_evaluated_at   TIMESTAMPTZ,
    last_fired_at       TIMESTAMPTZ,
    muted_until         TIMESTAMPTZ,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE routify_audit.alert_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id         UUID NOT NULL REFERENCES routify_audit.alert_rule(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,
    transition      VARCHAR(30) NOT NULL,
    metric_value    DECIMAL(12,4),
    threshold       DECIMAL(12,4),
    message         VARCHAR(1000),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_alert_rule_tenant ON routify_audit.alert_rule(tenant_id, enabled);
CREATE INDEX idx_alert_event_rule ON routify_audit.alert_event(rule_id, occurred_at DESC);

