-- Index to support the nightly webhook delivery cleanup scheduler.
-- The cleanup query deletes rows WHERE created_at < :cutoff ORDER BY created_at ASC LIMIT :batchSize.
-- Without this index, every cleanup batch requires a full table scan.
CREATE INDEX idx_webhook_delivery_created_at
    ON routify_identity.webhook_delivery(created_at ASC);

