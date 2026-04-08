package io.routify.gitops.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Spring configuration for the GitOps agent.
 *
 * <p>Provides a shared {@link RestTemplate} bean used by {@code AdminApiClient}
 * and {@code WebhookNotifier}. Connection and read timeouts prevent the agent
 * from hanging indefinitely when the admin-api or webhook endpoint is slow.
 */
@Configuration
public class GitOpsConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
