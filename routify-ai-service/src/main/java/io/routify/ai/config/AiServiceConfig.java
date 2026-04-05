package io.routify.ai.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.routify.ai.service.AiFilterEvaluationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Core AI service configuration.
 *
 * <h3>Provider: OpenAI (Chat Completions API)</h3>
 * Spring AI auto-configures an {@code OpenAiChatModel} bean based on
 * {@code spring.ai.openai.*} properties. We build a {@link ChatClient} from it
 * with shared defaults so every call inherits them without repetition.
 *
 * <h3>JSON output enforcement (dual layer)</h3>
 * <ol>
 *   <li><b>System prompt</b> — instructs the model to respond with ONLY a valid JSON object.</li>
 *   <li><b>OpenAI response_format</b> — {@code {"type": "json_object"}} set via
 *       {@link OpenAiChatOptions#getResponseFormat()} enforces JSON output at the API level,
 *       preventing markdown code fences and prose preambles entirely.</li>
 * </ol>
 * This dual enforcement is the most reliable approach for structured output with GPT models.
 *
 * <h3>Switching back to a local provider</h3>
 * Swap {@code spring-ai-starter-model-openai} for {@code spring-ai-starter-model-ollama}
 * in {@code pom.xml} and restore the {@code spring.ai.ollama} block in
 * {@code application.yml}. Remove the {@link OpenAiChatOptions} import and replace the
 * options builder below with {@code ChatOptions.builder()}. No other Java changes required.
 *
 * <h3>Async executor</h3>
 * A named {@code aiFilterExecutor} thread pool handles:
 * <ul>
 *   <li>ASYNC evaluation mode (fire-and-forget from gateway)</li>
 *   <li>Kafka telemetry publishing (non-blocking)</li>
 * </ul>
 * Pool size is tuned for OpenAI's network-I/O-bound workload. Threads mostly block on
 * HTTP; a larger pool (default 16) maximises throughput within OpenAI rate limits.
 */
@Configuration
@EnableAsync
public class AiServiceConfig {

    @Value("${routify.ai.async-pool-size:16}")
    private int asyncPoolSize;

    @Value("${routify.ai.async-queue-capacity:200}")
    private int asyncQueueCapacity;

    /**
     * Builds the provider-agnostic {@link ChatClient} with shared OpenAI defaults.
     *
     * <p>We use {@link OpenAiChatOptions} (instead of the generic {@code ChatOptions})
     * to set {@code responseFormat} to {@code JSON_OBJECT}. This instructs the OpenAI
     * API to guarantee that the model's output is a valid JSON object — eliminating
     * markdown code fences and prose that can trip up our JSON parser.
     *
     * <p><b>OpenAI requirement:</b> when {@code response_format.type = json_object} is set,
     * the word "json" must appear somewhere in the system or user prompt. Our system prompt
     * already satisfies this requirement.
     *
     * <p>Per-call overrides in {@link AiFilterEvaluationService}
     * only set provider-agnostic fields (temperature, maxTokens); the response format is
     * enforced at the bean level here so it applies to every call automatically.
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                // Use OpenAiChatOptions for the JSON response format enforcement.
                // temperature(0.0) is MANDATORY for deterministic, cacheable verdicts.
                // maxTokens(256) caps response length — model only needs a small JSON object.
                .defaultOptions(OpenAiChatOptions.builder()
                        .temperature(0.0)
                        .maxTokens(256)
                        // JSON_OBJECT mode: OpenAI guarantees valid JSON output.
                        // Eliminates markdown fences and prose; simplifies parsing.
                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                        .build())
                .build();
    }

    /**
     * Jackson {@link ObjectMapper} with Java time support.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Dedicated thread pool for async AI evaluation and Kafka publishing.
     *
     * <p>Sizing for OpenAI (network I/O bound):
     * <ul>
     *   <li>Threads mostly block on HTTP; a pool of 16 maximises concurrency within rate limits</li>
     *   <li>Each thread can serve ~3–5 req/s (OpenAI gpt-4o-mini p50 ~300ms)</li>
     *   <li>{@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} ensures
     *       the caller blocks rather than dropping requests when the queue is full</li>
     * </ul>
     */
    @Bean(name = "aiFilterExecutor")
    public Executor aiFilterExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, asyncPoolSize / 4));
        executor.setMaxPoolSize(asyncPoolSize);
        executor.setQueueCapacity(asyncQueueCapacity);
        executor.setThreadNamePrefix("ai-filter-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

