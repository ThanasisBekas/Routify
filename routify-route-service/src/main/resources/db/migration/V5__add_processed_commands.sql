-- V5: Idempotency table for Kafka command consumers.
-- Stores processed commandId (UUID) to prevent duplicate command execution
-- under Kafka's at-least-once delivery guarantee.

CREATE TABLE IF NOT EXISTS routify.processed_command (
    command_id   UUID         PRIMARY KEY,
    command_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_command_processed_at
    ON routify.processed_command (processed_at);

COMMENT ON TABLE routify.processed_command
    IS 'Idempotency ledger — one row per successfully processed Kafka command. Rows older than 7 days are safe to purge.';

