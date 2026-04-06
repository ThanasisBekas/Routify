-- =============================================================================
-- V4: LISTEN/NOTIFY trigger for outbox event-driven polling
-- =============================================================================
-- Reduces empty database round-trips by waking the OutboxPoller on demand
-- instead of polling every 250ms. The poller's scheduled interval becomes a
-- safety-net fallback (increased to 5s); real-time wake-ups come from pg_notify.
--
-- Channel: 'outbox_event_inserted' — the OutboxNotifyListener LISTENs on this.
-- =============================================================================

-- Notification function — fires pg_notify with minimal payload (just the event ID)
CREATE OR REPLACE FUNCTION routify.notify_outbox_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_notify('outbox_event_inserted', NEW.id::text);
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION routify.notify_outbox_insert() IS
    'Fires a pg_notify on channel "outbox_event_inserted" after each INSERT into '
    'routify.outbox_event. The OutboxNotifyListener picks this up and wakes the '
    'OutboxPoller immediately — eliminating empty 250ms polling cycles.';

-- Trigger — fires AFTER INSERT so it does not slow down the inserting transaction.
-- AFTER INSERT is used (not AFTER COMMIT) because pg_notify delivery is already
-- deferred to transaction commit by PostgreSQL — the listener only receives the
-- notification after the inserting transaction commits successfully.
CREATE OR REPLACE TRIGGER trg_outbox_event_notify
    AFTER INSERT ON routify.outbox_event
    FOR EACH ROW
    EXECUTE FUNCTION routify.notify_outbox_insert();

COMMENT ON TRIGGER trg_outbox_event_notify ON routify.outbox_event IS
    'Sends a pg_notify("outbox_event_inserted") after each outbox row is inserted. '
    'Used by OutboxNotifyListener to wake the OutboxPoller on demand.';

