package io.routify.common.event;

import java.time.Instant;
import java.util.List;

/**
 * Kafka event published to {@value KafkaTopics#AI_MODIFICATION_EVENTS} after every
 * AI Modification Filter evaluation.
 *
 * <p>Published by {@code routify-ai-service} and consumed by {@code routify-audit-service}.
 * Lives in {@code routify-common} so both services share the same wire format without
 * a circular module dependency.
 *
 * <h3>PII-safe design</h3>
 * The original and mutated request bodies are <strong>never</strong> stored in this event.
 * Only SHA-256 hashes are included, making the event safe for long-term retention and
 * Kafka log compaction without risk of PII leakage.
 *
 * @param mutationId          Unique trace ID correlating this event to the HTTP response.
 *                            Matches the {@code X-AI-Modifier-Id} header injected by the gateway.
 * @param routeId             Route on which the filter fired.
 * @param routeName           Human-readable route name.
 * @param tenantId            Owning tenant.
 * @param mutationApplied     Whether a mutation was actually made (false = passthrough).
 * @param mutationType        PII_SCRUB | TRANSLATE | HEADER_REWRITE | CUSTOM | PASSTHROUGH
 * @param reason              LLM-generated one-sentence explanation.
 * @param originalBodyHash    SHA-256 of the original request body bytes (hex-encoded).
 *                            {@code null} when body was not included in the evaluation.
 * @param mutatedBodyHash     SHA-256 of the mutated request body bytes (hex-encoded).
 *                            {@code null} when mutationApplied=false or body was not mutated.
 * @param headersModified     List of header <em>names</em> (not values) that were modified.
 *                            Empty when only body was mutated or no headers changed.
 * @param cached              Whether the mutation result was served from the Redis cache.
 * @param latencyMs           Total mutation evaluation latency in milliseconds.
 * @param method              HTTP method of the intercepted request.
 * @param path                Request path.
 * @param clientIp            Originating client IP.
 * @param evaluatedAt         Timestamp of evaluation.
 */
public record AiModificationDecisionEvent(
        String       mutationId,
        String       routeId,
        String       routeName,
        String       tenantId,
        boolean      mutationApplied,
        String       mutationType,
        String       reason,
        String       originalBodyHash,
        String       mutatedBodyHash,
        List<String> headersModified,
        boolean      cached,
        long         latencyMs,
        String       method,
        String       path,
        String       clientIp,
        Instant      evaluatedAt
) {}

