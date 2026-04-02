package gr.routify.gateway.auth.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Registers {@link AuthProperties} as a {@code @RefreshScope} bean via a {@code @Bean} factory method.
 * This avoids the double-proxy issue that arises when {@code @RefreshScope} is placed directly on a
 * {@code @ConfigurationProperties} class.
 */
@Configuration
public class AuthPropertiesConfig {

    /**
     * Produces {@link AuthProperties} bound to {@code auth.*};
     * recreated on every Config Server refresh.
     */
    @Bean
    @RefreshScope
    @Validated
    @ConfigurationProperties(prefix = "auth")
    public AuthProperties authProperties() {
        return new AuthProperties();
    }
}

