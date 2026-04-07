package io.routify.common.domain;

/**
 * Metric types available for platform alert rules.
 *
 * <p>Each metric maps to a specific data source in audit-service's
 * {@code AlertMetricResolver}. Rules reference a single metric; the
 * resolver queries the appropriate table/store to produce the current value.
 */
public enum AlertMetric {
    /** Percentage of 5xx responses in the evaluation window. */
    ERROR_RATE,
    /** 99th-percentile request latency in milliseconds. */
    P99_LATENCY,
    /** Number of unprocessed DLQ events in the evaluation window. */
    DLQ_DEPTH,
    /** Days until the nearest certificate expires. */
    CERT_EXPIRY_DAYS,
    /** Percentage of the monthly request quota consumed. */
    QUOTA_USAGE,
    /** Percentage of the SLO error budget consumed. */
    SLO_BUDGET,
    /** Requests per minute (spike/drop detection). */
    REQUEST_VOLUME,
    /** Percentage of auth failures (401/403) in the evaluation window. */
    AUTH_FAILURE_RATE
}

