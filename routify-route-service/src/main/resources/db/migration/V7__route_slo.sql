-- V7: Route SLO configuration table for Gateway Health Dashboard v2.
-- Stores per-route SLO targets (availability, p99 latency, evaluation window).

CREATE TABLE routify.route_slo (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id                UUID NOT NULL UNIQUE REFERENCES routify.route(id) ON DELETE CASCADE,
    availability_target     DECIMAL(5,2) NOT NULL DEFAULT 99.90,
    latency_p99_target_ms   INT NOT NULL DEFAULT 1000,
    evaluation_window_hours INT NOT NULL DEFAULT 168,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_slo_route_id ON routify.route_slo(route_id);

