package gr.routify.audit.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.audit.domain.RequestLog;
import gr.routify.audit.repository.RequestLogRepository;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumes request telemetry events published by the API Gateway.
 * Persists per-request logs for analytics, debugging, SLA monitoring, and replay.
 *
 * <p>Every request that passes through the gateway is recorded here regardless of outcome.
 * Requests that fail with 5xx or exceptions are flagged with {@code failed=true} and
 * given an initial {@code replayStatus=PENDING}, making them eligible for replay via
 * routify-admin-api at {@code /api/v1/admin/audit/replay}.
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
    public void onRequestTelemetry(String json, Acknowledgment ack) {
        try {
            JsonNode node = objectMapper.readTree(json);

            boolean failed = node.path("failed").asBoolean(false);


            RequestLog entry = RequestLog.builder()
                    .tenantId(parseUuid(node, "tenantId"))
                    .routeId(parseUuid(node, "routeId"))
                    .routeName(node.path("routeName").asText(null))
                    .correlationId(node.path("correlationId").asText(null))
                    .httpMethod(node.path("method").asText(null))
                    .path(node.path("path").asText(null))
                    .queryString(node.path("queryString").asText(null))
                    .upstreamUri(node.path("upstreamUri").asText(null))
                    .responseStatus(node.path("responseStatus").asInt(0))
                    .durationMs(node.path("durationMs").asLong(0))
                    .requestSizeBytes(node.path("requestSizeBytes").asLong(0))
                    .responseSizeBytes(node.path("responseSizeBytes").asLong(0))
                    .clientIp(node.path("clientIp").asText(null))
                    .userId(node.path("userId").asText(null))
                    .errorMessage(node.path("errorMessage").asText(null))
                    .filterTrace(node.has("filterTrace") ? node.path("filterTrace").toString() : null)
                    .requestHeaders(extractJsonField(node, "requestHeaders"))
                    .responseHeaders(extractJsonField(node, "responseHeaders"))
                    .requestBody(node.has("requestBody") && !node.path("requestBody").isNull()
                            ? node.path("requestBody").asText(null) : null)
                    .responseBody(node.has("responseBody") && !node.path("responseBody").isNull()
                            ? node.path("responseBody").asText(null) : null)
                    .failed(failed)
                    .replayStatus(failed ? "PENDING" : null)
                    .requestedAt(node.has("requestedAt")
                            ? Instant.parse(node.path("requestedAt").asText())
                            : Instant.now())
                    .build();

            requestLogRepository.save(entry);
            ack.acknowledge();

            if (failed) {
                log.info("Failed request recorded for replay: correlationId={} status={} path={}",
                        entry.getCorrelationId(), entry.getResponseStatus(), entry.getPath());
            }

        } catch (Exception e) {
            log.error("Failed to process request telemetry: {}", e.getMessage(), e);
        }
    }

    private String extractJsonField(JsonNode node, String field) {
        if (node.has(field) && !node.path(field).isNull()) {
            JsonNode child = node.path(field);
            return child.isTextual() ? child.asText() : child.toString();
        }
        return null;
    }

    private UUID parseUuid(JsonNode node, String field) {
        try {
            String val = node.path(field).asText(null);
            return val != null && !val.isEmpty() ? UUID.fromString(val) : null;
        } catch (Exception e) {
            return null;
        }
    }
}



