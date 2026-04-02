package gr.routify.gateway.downstream.oauth2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires {@link CaffeineOauth2TokenCache} to {@link Oauth2AccessTokenProvider} via a {@code @Bean} factory.
 * Using {@code @Lazy} breaks the circular dependency and prevents {@code this}-escape.
 * Dispatches to the correct loader by cache-key prefix ({@code "password:"} vs {@code "cc:"}).
 */
@Configuration
public class TokenCacheConfig {

    @Bean
    public CaffeineOauth2TokenCache caffeineOauth2TokenCache(@Lazy Oauth2AccessTokenProvider provider) {
        return new CaffeineOauth2TokenCache(cacheKey -> {
            if (cacheKey.startsWith("cc:")) {
                return provider.fetchClientCredentialsToken(cacheKey);
            }
            return provider.fetchPasswordGrantToken(cacheKey);
        });
    }
}

