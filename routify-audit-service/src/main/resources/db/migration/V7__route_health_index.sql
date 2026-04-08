-- V7: Add composite index for route health dashboard queries.
-- Covers the per-route aggregation with time-range filter used by
-- QUEUE_AUDIT_ROUTE_HEALTH (Gateway Health Dashboard v2).

CREATE INDEX IF NOT EXISTS idx_request_log_route_requested
    ON routify_audit.request_log (tenant_id, route_id, requested_at DESC);

