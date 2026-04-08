package io.routify.gateway.downstream.oauth2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.gateway.auth.properties.AuthProperties;
import io.routify.gateway.auth.properties.ParameterStyle;
import io.routify.gateway.net.HttpClientProperties;
import io.routify.gateway.net.WebClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

import static io.routify.gateway.auth.util.AuthenticationUtils.buildBasicAuthorizationHeader;

/**
 * Calls a configured OAuth2 introspection endpoint to verify a Bearer token
 * and returns the parsed claims.
 */
@Slf4j
@Service
public class Oauth2BearerTokenVerifier {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final AuthProperties authProperties;
    private final WebClientRegistry webClientRegistry;
    private final ObjectMapper objectMapper;

    public Oauth2BearerTokenVerifier(AuthProperties authProperties,
                                     WebClientRegistry webClientRegistry,
                                     ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.webClientRegistry = webClientRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies the provided Bearer token against the configured provider.
     *
     * @param oauth2ProviderName the OAuth2 verification provider name
     * @param token              the bearer token to verify
     * @return a {@link Mono} emitting the {@link TokenVerificationResponse}
     */
    public Mono<TokenVerificationResponse> verifyToken(String oauth2ProviderName, String token) {
        AuthProperties.Oauth2VerificationConfig config =
                authProperties.getOauth2Verification().get(oauth2ProviderName);
        if (config == null) {
            return Mono.error(new IllegalStateException(
                    "No OAuth2 verification configuration found for provider '%s'"
                            .formatted(oauth2ProviderName)));
        }

        WebClient client = resolveWebClient(oauth2ProviderName, config);

        return buildVerificationRequest(client, config, token)
                .exchangeToMono(this::toVerificationResponse)
                .doOnNext(resp -> log.debug("Token verification for provider '{}': status={}",
                        oauth2ProviderName, resp.statusCode()))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to verify Bearer token against provider '%s'"
                                        .formatted(oauth2ProviderName), e));
    }

    /**
     * Verifies the provided Bearer token against a dynamically-configured introspection
     * endpoint — bypassing the static {@link AuthProperties} YAML configuration.
     *
     * <p>This overload is used when the {@code AUTH_OAUTH2} filter has direct config fields
     * populated from a {@code gatewayConfigRef} resolution (P-04 initiative).
     *
     * @param introspectUri                URI of the introspection endpoint
     * @param clientId                     client ID for Basic auth on the introspection call
     * @param clientSecret                 client secret for Basic auth on the introspection call
     * @param parameterStyle               how to pass the token (BODY, QUERY, or HEADER)
     * @param parameterName                form field / query param / header name for the token
     * @param contentType                  optional Content-Type (defaults to form-urlencoded)
     * @param includeBasicClientAuthorization whether to include Basic auth header
     * @param token                        the bearer token to verify
     * @return a {@link Mono} emitting the {@link TokenVerificationResponse}
     */
    public Mono<TokenVerificationResponse> verifyToken(String introspectUri,
                                                        String clientId,
                                                        String clientSecret,
                                                        ParameterStyle parameterStyle,
                                                        String parameterName,
                                                        String contentType,
                                                        boolean includeBasicClientAuthorization,
                                                        String token) {
        // Build an ad-hoc Oauth2VerificationConfig so we can reuse the existing
        // buildVerificationRequest / resolveWebClient infrastructure.
        AuthProperties.Oauth2VerificationConfig config = new AuthProperties.Oauth2VerificationConfig();
        config.setUri(introspectUri);
        config.setClientId(clientId);
        config.setClientSecret(clientSecret);
        config.setParameterStyle(parameterStyle != null ? parameterStyle : ParameterStyle.BODY);
        config.setParameterName(parameterName != null && !parameterName.isBlank() ? parameterName : "token");
        config.setContentType(contentType);
        config.setIncludeBasicClientAuthorization(includeBasicClientAuthorization);
        config.setConnectionConfig(new HttpClientProperties());

        // Use a cache key based on the introspection URI host to pool WebClients
        String cacheKey = "verify:dynamic:" + URI.create(introspectUri).getHost();
        WebClient client = webClientRegistry.get(cacheKey, config, config.getProxyConfig(),
                config.getConnectionConfig());

        return buildVerificationRequest(client, config, token)
                .exchangeToMono(this::toVerificationResponse)
                .doOnNext(resp -> log.debug("Token verification via dynamic config ({}): status={}",
                        introspectUri, resp.statusCode()))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to verify Bearer token against dynamic provider at '%s'"
                                        .formatted(introspectUri), e));
    }

    private WebClient.RequestHeadersSpec<?> buildVerificationRequest(
            WebClient client, AuthProperties.Oauth2VerificationConfig config, String token) {

        ParameterStyle style = config.getParameterStyle();
        String pathAndQuery = URI.create(config.getUri()).getRawPath();

        if (ParameterStyle.QUERY == style) {
            String uriWithToken = UriComponentsBuilder.fromPath(pathAndQuery)
                    .queryParam(config.getParameterName(), token)
                    .build().toUriString();
            WebClient.RequestBodySpec spec = client.post().uri(uriWithToken);
            applyCommonHeaders(spec, config);
            return spec;
        }

        if (ParameterStyle.BODY == style) {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add(config.getParameterName(), token);
            WebClient.RequestBodySpec spec = client.post().uri(pathAndQuery);
            applyCommonHeaders(spec, config);
            MediaType contentType = config.getContentType() != null
                    ? MediaType.parseMediaType(config.getContentType())
                    : MediaType.APPLICATION_FORM_URLENCODED;
            spec.contentType(contentType);
            return spec.body(BodyInserters.fromFormData(formData));
        }

        // Default: token as custom header
        WebClient.RequestBodySpec spec = client.post().uri(pathAndQuery);
        applyCommonHeaders(spec, config);
        spec.header(config.getParameterName(), token);
        return spec;
    }

    private void applyCommonHeaders(WebClient.RequestBodySpec spec,
                                     AuthProperties.Oauth2VerificationConfig config) {
        if (config.isIncludeBasicClientAuthorization()) {
            spec.header(HttpHeaders.AUTHORIZATION, buildBasicAuthorizationHeader(config));
        }
        if (config.getContentType() != null) {
            spec.header(HttpHeaders.CONTENT_TYPE, config.getContentType());
        }
    }

    private Mono<TokenVerificationResponse> toVerificationResponse(ClientResponse clientResponse) {
        int statusCode = clientResponse.statusCode().value();
        return clientResponse.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    Map<String, Object> claims = parseJsonSafe(body);
                    return new TokenVerificationResponse(statusCode, body, claims);
                });
    }

    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE_REF);
        } catch (Exception e) {
            log.warn("Failed to parse verification response body as JSON", e);
            return Map.of();
        }
    }

    private WebClient resolveWebClient(String providerName,
                                        AuthProperties.Oauth2VerificationConfig config) {
        HttpClientProperties connConfig = config.getConnectionConfig() != null
                ? config.getConnectionConfig()
                : new HttpClientProperties();
        return webClientRegistry.get("verify:" + providerName, config,
                config.getProxyConfig(), connConfig);
    }
}

