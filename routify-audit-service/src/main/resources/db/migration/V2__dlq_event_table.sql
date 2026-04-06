-- V2: DLQ event table
-- Stores every record forwarded to a Dead-Letter Queue topic by any Routify service.
-- Partitioned by month on failed_at for the same retention/query efficiency as audit_log.

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    source_topic    VARCHAR(200) NOT NULL,
    dlq_topic       VARCHAR(200) NOT NULL,
    partition_num   INTEGER,
    kafka_offset    BIGINT,
    record_key      VARCHAR(500),
    -- Raw payload preserved as-is — may be unparseable JSON, hence TEXT not JSONB
    raw_payload     TEXT,
    error_message   TEXT,
    -- Stack trace of the exception that triggered DLQ routing (first 4000 chars)
    error_class     VARCHAR(500),
    failed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_dlq_event PRIMARY KEY (id, failed_at)
) PARTITION BY RANGE (failed_at);

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event_y2026m01
    PARTITION OF routify_audit.dlq_event
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event_y2026m02
    PARTITION OF routify_audit.dlq_event
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event_y2026m03
    PARTITION OF routify_audit.dlq_event
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event_y2026m04
    PARTITION OF routify_audit.dlq_event
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event_y2026m05
    PARTITION OF routify_audit.dlq_event
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS routify_audit.dlq_event_y2026m06
    PARTITION OF routify_audit.dlq_event
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE INDEX idx_dlq_source_topic  ON routify_audit.dlq_event (source_topic, failed_at DESC);
CREATE INDEX idx_dlq_failed_at     ON routify_audit.dlq_event (failed_at DESC);

COMMENT ON TABLE  routify_audit.dlq_event                  IS 'Records every message forwarded to a .DLQ topic by any Routify service';
COMMENT ON COLUMN routify_audit.dlq_event.source_topic     IS 'The original topic the message was published to (without .DLQ suffix)';
COMMENT ON COLUMN routify_audit.dlq_event.dlq_topic        IS 'The actual DLQ topic the record landed on (e.g. routify.route.events.DLQ)';
COMMENT ON COLUMN routify_audit.dlq_event.raw_payload      IS 'Raw message value as received — preserved as TEXT in case JSON parsing failed';
COMMENT ON COLUMN routify_audit.dlq_event.error_message    IS 'Exception message that caused the record to be dead-lettered';
COMMENT ON COLUMN routify_audit.dlq_event.error_class      IS 'Fully-qualified class name of the exception';

