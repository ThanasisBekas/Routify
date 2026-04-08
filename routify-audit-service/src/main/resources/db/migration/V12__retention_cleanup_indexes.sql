-- V12: Retention cleanup indexes
-- Dedicated ASC-ordered indexes for efficient batch deletion by the
-- AuditRetentionScheduler. The existing composite indexes (tenant_id, *_at DESC)
-- are optimised for query patterns, not for the "DELETE WHERE *_at < cutoff" pattern.

CREATE INDEX IF NOT EXISTS idx_request_log_retention
    ON routify_audit.request_log (requested_at ASC);

CREATE INDEX IF NOT EXISTS idx_audit_log_retention
    ON routify_audit.audit_log (occurred_at ASC);

CREATE INDEX IF NOT EXISTS idx_ai_filter_decision_retention
    ON routify_audit.ai_filter_decision (evaluated_at ASC);

CREATE INDEX IF NOT EXISTS idx_ai_modifier_decision_retention
    ON routify_audit.ai_modifier_decision (evaluated_at ASC);

CREATE INDEX IF NOT EXISTS idx_alert_event_retention
    ON routify_audit.alert_event (occurred_at ASC);

CREATE INDEX IF NOT EXISTS idx_tenant_usage_daily_retention
    ON routify_audit.tenant_usage_daily (date ASC);

