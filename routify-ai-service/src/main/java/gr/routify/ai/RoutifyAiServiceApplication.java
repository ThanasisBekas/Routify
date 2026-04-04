package gr.routify.ai;

import gr.routify.common.config.SecretValidator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Routify AI Service — LLM-powered request filtering intelligence layer.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Receives route request metadata from the API Gateway</li>
 *   <li>Constructs structured prompts from the request context and the operator-defined
 *       policy description</li>
 *   <li>Delegates to an LLM via Spring AI's {@code ChatClient} abstraction
 *       (provider-swappable: OpenAI, Ollama, Anthropic, …)</li>
 *   <li>Returns an {@code AiVerdict} (ALLOW / BLOCK / FLAG) to the gateway within
 *       a hard timeout, with a safe fallback when the LLM is degraded</li>
 *   <li>Caches verdicts in Redis to eliminate repeated LLM calls for identical requests</li>
 *   <li>Publishes {@code AiFilterDecisionEvent}s to Kafka for the audit trail</li>
 * </ul>
 *
 * <h2>Port allocations</h2>
 * <ul>
 *   <li>8086 — application HTTP</li>
 *   <li>9086 — management / actuator (Prometheus scrape)</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableCaching
public class RoutifyAiServiceApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyAiServiceApplication.class)
                .listeners(new SecretValidator())
                .run(args);
    }
}

