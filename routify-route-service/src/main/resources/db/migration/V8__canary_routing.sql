-- V8: Canary Routing & Traffic Splitting (Initiative 14)
-- Adds weighted traffic splitting support between route versions.

ALTER TABLE routify.route
    ADD COLUMN traffic_weight    INT NOT NULL DEFAULT 100,
    ADD COLUMN canary_route_id   UUID REFERENCES routify.route(id) ON DELETE SET NULL,
    ADD COLUMN canary_auto_rollback_threshold DECIMAL(5,2);

-- Constraint: traffic_weight between 0 and 100
ALTER TABLE routify.route ADD CONSTRAINT chk_traffic_weight
    CHECK (traffic_weight >= 0 AND traffic_weight <= 100);

