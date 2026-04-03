package gr.routify.ai.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Core AI service configuration.
 *
 * <h3>Provider: Ollama (local inference)</h3>
 * Spring AI auto-configures an {@code OllamaChatModel} bean based on
 * {@code spring.ai.ollama.*} properties. We build a {@link ChatClient} from it
 * with shared defaults so every call inherits them without repetition.
 *
 * <h3>Switching back to a cloud provider</h3>
 * Swap {@code spring-ai-starter-model-ollama} for {@code spring-ai-starter-model-openai}
 * in {@code pom.xml} and restore the {@code spring.ai.openai} block in
 * {@code application.yml}. This class requires NO modification — {@link ChatModel}
 * is injected by Spring AI regardless of provider.
 *
 * <h3>Async executor</h3>
 * A named {@code aiFilterExecutor} thread pool handles:
 * <ul>
 *   <li>ASYNC evaluation mode (fire-and-forget from gateway)</li>
 *   <li>Kafka telemetry publishing (non-blocking)</li>
 * </ul>
 * Pool size is tuned for local Ollama — I/O-bound but Ollama serialises
 * concurrent requests on CPU, so a smaller pool (default 16) avoids
 * thundering-herd pressure on the inference server.
 */
@Configuration
@EnableAsync
public class AiServiceConfig {

    @Value("${routify.ai.async-pool-size:16}")
    private int asyncPoolSize;

    @Value("${routify.ai.async-queue-capacity:200}")
    private int asyncQueueCapacity;

    /**
     * Builds the provider-agnostic {@link ChatClient} with shared defaults.
     *
     * <p>For Ollama, we use {@link org.springframework.ai.ollama.api.OllamaOptions}
     * to set {@code numPredict} (Ollama's token-limit field) and {@code seed} for
     * maximum determinism. These are ignored by non-Ollama providers.
     *
     * <p>Per-call overrides in {@link gr.routify.ai.service.AiFilterEvaluationService}
     * only set provider-agnostic fields (temperature); Ollama-specific options come from
     * the application.yml via Spring AI's auto-configuration.
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(org.springframework.ai.chat.prompt.ChatOptions.builder()
                        .temperature(0.0)   // mandatory for cache determinism
                        .maxTokens(256)     // maps to num_predict in Ollama wire format
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
     * <p>Sizing for local Ollama:
     * <ul>
     *   <li>CPU-only: keep pool ≤ 4–8; Ollama serialises requests anyway</li>
     *   <li>GPU: pool = number_of_GPUs × 4 for parallel batch throughput</li>
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

