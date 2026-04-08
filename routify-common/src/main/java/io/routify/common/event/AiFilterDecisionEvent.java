package io.routify.common.event;

import java.time.Instant;

/**
 * Kafka event published to {@value KafkaTopics#AI_FILTER_DECISIONS} after every evaluation.
 *
 * <p>Published by {@code routify-ai-service} and consumed by {@code routify-audit-service}.
 * Lives in {@code routify-common} so both services share the same wire format without
 * a circular module dependency.
 *
 * @param evaluationId   Unique trace ID correlating this event to the HTTP response.
 * @param routeId        Route on which the filter fired.
 * @param routeName      Human-readable route name.
 * @param tenantId       Owning tenant.
 * @param action         ALLOW | BLOCK | FLAG
 * @param reason         LLM-generated explanation.
 * @param confidence     LLM confidence score (0.0–1.0); 0.0 for fallback verdicts.
 * @param cached         Whether the verdict came from the Redis cache.
 * @param evaluationMode SYNC | ASYNC
 * @param latencyMs      Total evaluation latency in milliseconds.
 * @param method         HTTP method of the intercepted request.
 * @param path           Request path.
 * @param clientIp       Originating client IP.
 * @param evaluatedAt    Timestamp of evaluation.
 * @param promptVersionId UUID of the prompt version used (null if no A/B split).
 */
public record AiFilterDecisionEvent(
        String  evaluationId,
        String  routeId,
        String  routeName,
        String  tenantId,
        String  action,
        String  reason,
        double  confidence,
        boolean cached,
        String  evaluationMode,
        long    latencyMs,
        String  method,
        String  path,
        String  clientIp,
        Instant evaluatedAt,
        String  promptVersionId
) {}

