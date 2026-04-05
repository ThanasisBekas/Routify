package io.routify.common.web;

/**
 * Standard body for asynchronous command endpoints (HTTP 202 Accepted).
 *
 * <p>All write operations that are dispatched as Kafka command events return this
 * record. The caller should poll the appropriate read endpoint or subscribe to
 * the SSE stream for the resulting domain event.
 *
 * @param status  always {@code "accepted"}
 * @param message human-readable description of the in-progress operation
 */
public record AsyncAcknowledgement(String status, String message) {

    /** Convenience factory. */
    public static AsyncAcknowledgement of(String message) {
        return new AsyncAcknowledgement("accepted", message);
    }
}

