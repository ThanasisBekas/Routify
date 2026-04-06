package io.routify.gateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.routify.gateway.auth.properties.ClientProperties;
import io.routify.gateway.net.HttpClientProperties;
import io.routify.gateway.net.ProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Gateway application configuration.
 */
@Configuration
@EnableConfigurationProperties({ClientProperties.class})
public class GatewayConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json");
    }

    /**
     * Shared, mutable proxy settings bean — updated at runtime by
     * {@link GatewayResilienceConfigApplier} when the admin saves networking config.
     */
    @Bean
    public ProxyProperties globalProxyProperties() {
        return new ProxyProperties();
    }

    /**
     * Shared, mutable HTTP client pool/timeout settings bean — updated at runtime by
     * {@link GatewayResilienceConfigApplier} when the admin saves networking config.
     */
    @Bean
    public HttpClientProperties globalHttpClientProperties() {
        return new HttpClientProperties();
    }
}

