package io.routify.common.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Typed event published by routify-api-gateway to the {@link KafkaTopics#REQUEST_TELEMETRY} topic.
 *
 * <p>Represents a single completed HTTP request (successful or failed) that passed through the
 * gateway. Consumed by routify-audit-service for per-request audit logging, analytics, SLA
 * monitoring, and replay.
 *
 * <p>All fields map 1-to-1 to the database columns in {@code RequestLog}. {@link Instant} fields
 * are rendered as ISO-8601 strings by Jackson.
 *
 * <p>Replaces the previous {@code GatewayTelemetryPublisher.TelemetryEvent} local record and the
 * raw {@code String json} / {@link com.fasterxml.jackson.databind.JsonNode} pattern used in the
 * consumer, ensuring type-safe access on both ends of the Kafka topic.
 */
public record RequestTelemetryEvent(
        String  correlationId,
        String  tenantId,
        String  routeId,
        String  routeName,
        String  method,
        String  path,
        String  queryString,
        String  upstreamUri,
        String  clientIp,
        String  userId,
        Integer responseStatus,
        Long    durationMs,
        Long    requestSizeBytes,
        Long    responseSizeBytes,
        String  errorMessage,
        boolean failed,
        Instant requestedAt,
        List<FilterSpan>        filterTrace,
        Map<String, String>     requestHeaders,
        Map<String, String>     responseHeaders,
        String  requestBody,
        String  responseBody
) {
    /**
     * Represents the execution span of a single filter in the chain.
     */
    public record FilterSpan(String filterName, long durationMs, String outcome) {}
}

