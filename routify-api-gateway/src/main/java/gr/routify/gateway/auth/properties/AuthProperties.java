package gr.routify.gateway.auth.properties;

import gr.routify.gateway.net.HttpClientProperties;
import gr.routify.gateway.net.ProxyProperties;
import gr.routify.gateway.ssl.SSLContextProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for all authentication providers ({@code auth.*}).
 * Registered as a refresh-scoped bean — see AuthPropertiesConfig.
 */
@Data
@Validated
public class AuthProperties {

    private Map<String, BasicAuthConfig> basic = new HashMap<>();

    @Valid
    private Map<String, Oauth2Config> oauth2 = new HashMap<>();

    @Valid
    private Map<String, Oauth2VerificationConfig> oauth2Verification = new HashMap<>();

    @Data
    public static class BasicAuthConfig {
        private String username;
        private String password;
    }

    /**
     * Config for acquiring tokens from an OAuth2 provider (password grant / client credentials).
     * {@code uri} is required.
     */
    @Data
    @Validated
    @EqualsAndHashCode(callSuper = false)
    public static class Oauth2Config extends SSLContextProperties {

        private String username;
        private String password;

        private String scope;
        private String clientId;
        private String clientSecret;
        private boolean includeBasicClientAuthorization;
        private ProxyProperties proxyConfig;
        private HttpClientProperties connectionConfig = new HttpClientProperties();
    }

    /**
     * Config for verifying Bearer tokens against an OAuth2 introspection endpoint.
     * {@code parameterStyle} and {@code parameterName} are required.
     */
    @Data
    @Validated
    @EqualsAndHashCode(callSuper = false)
    public static class Oauth2VerificationConfig extends Oauth2Config {

        @NotNull(message = "OAuth2 verification parameterStyle must not be null (use QUERY, BODY, or HEADER)")
        private ParameterStyle parameterStyle;

        @NotBlank(message = "OAuth2 verification parameterName must not be blank")
        private String parameterName;

        private String contentType;
    }
}

