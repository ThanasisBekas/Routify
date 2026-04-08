package io.routify.audit.consumer;

import io.routify.audit.domain.AiFilterDecision;
import io.routify.audit.repository.AiFilterDecisionRepository;
import io.routify.common.event.AiFilterDecisionEvent;
import io.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Kafka consumer for AI filter decision telemetry events.
 *
 * <p>Every verdict produced by {@code routify-ai-service} — ALLOW, BLOCK, FLAG, or
 * circuit-breaker fallback — is published to {@link KafkaTopics#AI_FILTER_DECISIONS}
 * and persisted here into {@code routify_audit.ai_filter_decision} for:
 * <ul>
 *   <li>Compliance review and forensics (which requests were blocked and why)</li>
 *   <li>Accuracy monitoring (ALLOW/BLOCK/FLAG breakdown per route over time)</li>
 *   <li>LLM latency and cache-hit dashboards in Grafana</li>
 *   <li>Policy effectiveness analysis (via admin-api AI stats endpoint)</li>
 * </ul>
 *
 * <p>Mirrors the pattern of {@link RequestTelemetryConsumer} — same group factory,
 * same ack mode, same error handling (DLQ after max retries).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFilterDecisionConsumer {

    private final AiFilterDecisionRepository decisionRepository;

    @KafkaListener(
            topics           = KafkaTopics.AI_FILTER_DECISIONS,
            groupId          = "routify-audit-ai-filter",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onAiFilterDecision(AiFilterDecisionEvent event, Acknowledgment ack) {
        try {
            AiFilterDecision record = AiFilterDecision.builder()
                    .evaluationId(event.evaluationId())
                    .routeId(parseUuid(event.routeId()))
                    .routeName(event.routeName())
                    .tenantId(parseUuid(event.tenantId()))
                    .action(event.action())
                    .reason(event.reason())
                    .confidence(event.confidence())
                    .cached(event.cached())
                    .evaluationMode(event.evaluationMode() != null ? event.evaluationMode() : "SYNC")
                    .latencyMs(event.latencyMs())
                    .method(event.method())
                    .path(event.path())
                    .clientIp(event.clientIp())
                    .evaluatedAt(event.evaluatedAt() != null ? event.evaluatedAt() : java.time.Instant.now())
                    .promptVersionId(parseUuid(event.promptVersionId()))
                    .build();

            decisionRepository.save(record);
            ack.acknowledge();

            log.debug("AI filter decision persisted: evalId={} routeId={} action={} cached={} latencyMs={}",
                    event.evaluationId(), event.routeId(), event.action(),
                    event.cached(), event.latencyMs());

            // Surface BLOCK decisions prominently for ops visibility
            if ("BLOCK".equals(event.action())) {
                log.info("AI filter BLOCK recorded: route={} ({}) reason='{}'",
                        event.routeId(), event.routeName(), event.reason());
            }

        } catch (Exception e) {
            log.error("Failed to persist AI filter decision: evalId={} error={}",
                    event != null ? event.evaluationId() : "null", e.getMessage(), e);
            // Do NOT acknowledge — let Kafka retry via the DLQ error handler
            throw new RuntimeException("Failed to persist AI filter decision", e);
        }
    }

    private UUID parseUuid(String value) {
        try {
            return value != null && !value.isBlank() ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

