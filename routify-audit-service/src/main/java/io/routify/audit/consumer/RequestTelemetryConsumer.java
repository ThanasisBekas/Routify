package io.routify.audit.consumer;

import io.routify.audit.domain.RequestLog;
import io.routify.audit.repository.RequestLogRepository;
import io.routify.common.event.KafkaTopics;
import io.routify.common.event.RequestTelemetryEvent;
import io.routify.common.exception.RoutifyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes request telemetry events published by the API Gateway.
 * Persists per-request logs for analytics, debugging, SLA monitoring, and replay.
 *
 * <p>Every request that passes through the gateway is recorded here regardless of outcome.
 * Requests that fail with 5xx or exceptions are flagged with {@code failed=true} and
 * given an initial {@code replayStatus=PENDING}, making them eligible for replay via
 * routify-admin-api at {@code /api/v1/admin/audit/replay}.
 *
 * <p>Uses the typed {@link RequestTelemetryEvent} record from {@code routify-common}
 * for type-safe, direct field access — no raw JSON or {@code JsonNode} parsing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestTelemetryConsumer {

    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.REQUEST_TELEMETRY,
            groupId = "routify-audit-telemetry",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onRequestTelemetry(RequestTelemetryEvent event, Acknowledgment ack) {
        try {
            RequestLog entry = RequestLog.builder()
                    .tenantId(parseUuid(event.tenantId()))
                    .routeId(parseUuid(event.routeId()))
                    .routeName(event.routeName())
                    .correlationId(event.correlationId())
                    .httpMethod(event.method())
                    .path(event.path())
                    .queryString(event.queryString())
                    .upstreamUri(event.upstreamUri())
                    .responseStatus(event.responseStatus())
                    .durationMs(event.durationMs())
                    .requestSizeBytes(event.requestSizeBytes())
                    .responseSizeBytes(event.responseSizeBytes())
                    .clientIp(event.clientIp())
                    .userId(event.userId())
                    .errorMessage(event.errorMessage())
                    .filterTrace(toJson(event.filterTrace()))
                    .requestHeaders(toJson(event.requestHeaders()))
                    .responseHeaders(toJson(event.responseHeaders()))
                    .requestBody(event.requestBody())
                    .responseBody(event.responseBody())
                    .failed(event.failed())
                    .replayStatus(event.failed() ? "PENDING" : null)
                    .requestedAt(event.requestedAt() != null ? event.requestedAt() : java.time.Instant.now())
                    .build();

            requestLogRepository.save(entry);
            ack.acknowledge();

            if (event.failed()) {
                log.info("Failed request recorded for replay: correlationId={} status={} path={}",
                        entry.getCorrelationId(), entry.getResponseStatus(), entry.getPath());
            }

        } catch (Exception e) {
            log.error("Failed to process request telemetry: {}", e.getMessage(), e);
            // Do NOT acknowledge — let Kafka retry. After max retries the error handler
            // will route the record to the DLQ (configured in AuditServiceConfig).
            throw new RoutifyException.GatewayError("Failed to process request telemetry", e);
        }
    }

    private UUID parseUuid(String value) {
        try {
            return value != null && !value.isEmpty() ? UUID.fromString(value) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize field to JSON, storing as null: {}", e.getMessage());
            return null;
        }
    }
}
