package gr.routify.audit.consumer;

import gr.routify.audit.domain.AiModificationDecision;
import gr.routify.audit.repository.AiModificationDecisionRepository;
import gr.routify.common.event.AiModificationDecisionEvent;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Kafka consumer for AI Modification Filter decision telemetry events.
 *
 * <p>Every mutation result produced by {@code routify-ai-service} — whether a mutation
 * was applied or a passthrough occurred — is published to
 * {@link KafkaTopics#AI_MODIFICATION_EVENTS} and persisted here into
 * {@code routify_audit.ai_modifier_decision} for:
 * <ul>
 *   <li>Compliance review (which requests were mutated and how)</li>
 *   <li>PII scrubbing audit trail (SHA-256 hashes, not raw data)</li>
 *   <li>LLM latency and mutation rate dashboards in Grafana</li>
 *   <li>Policy effectiveness analysis via admin-api AI modifier stats endpoint</li>
 * </ul>
 *
 * <p>Mirrors the pattern of {@link AiFilterDecisionConsumer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModificationDecisionConsumer {

    private final AiModificationDecisionRepository modificationRepository;

    @KafkaListener(
            topics           = KafkaTopics.AI_MODIFICATION_EVENTS,
            groupId          = "routify-audit-ai-modifier",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onAiModificationDecision(AiModificationDecisionEvent event, Acknowledgment ack) {
        try {
            AiModificationDecision record = AiModificationDecision.builder()
                    .mutationId(event.mutationId())
                    .routeId(parseUuid(event.routeId()))
                    .routeName(event.routeName())
                    .tenantId(parseUuid(event.tenantId()))
                    .mutationApplied(event.mutationApplied())
                    .mutationType(event.mutationType())
                    .reason(event.reason())
                    .originalBodyHash(event.originalBodyHash())
                    .mutatedBodyHash(event.mutatedBodyHash())
                    .headersModified(event.headersModified())
                    .cached(event.cached())
                    .latencyMs(event.latencyMs())
                    .method(event.method())
                    .path(event.path())
                    .clientIp(event.clientIp())
                    .evaluatedAt(event.evaluatedAt() != null ? event.evaluatedAt() : java.time.Instant.now())
                    .build();

            modificationRepository.save(record);
            ack.acknowledge();

            log.debug("AI modification decision persisted: mutationId={} routeId={} applied={} type={} latencyMs={}",
                    event.mutationId(), event.routeId(), event.mutationApplied(),
                    event.mutationType(), event.latencyMs());

            // Surface applied mutations prominently for ops visibility
            if (event.mutationApplied()) {
                log.info("AI modifier APPLIED: route={} ({}) type='{}' reason='{}'",
                        event.routeId(), event.routeName(), event.mutationType(), event.reason());
            }

        } catch (Exception e) {
            log.error("Failed to persist AI modification decision: mutationId={} error={}",
                    event != null ? event.mutationId() : "null", e.getMessage(), e);
            throw new RuntimeException("Failed to persist AI modification decision", e);
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

