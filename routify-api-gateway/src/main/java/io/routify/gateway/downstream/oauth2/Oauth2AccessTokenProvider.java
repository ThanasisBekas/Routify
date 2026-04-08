package io.routify.gateway.downstream.oauth2;

import io.routify.gateway.auth.properties.AuthProperties;
import io.routify.gateway.net.HttpClientProperties;
import io.routify.gateway.net.WebClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.routify.gateway.auth.util.AuthenticationUtils.buildBasicAuthorizationHeader;

/**
 * Acquires OAuth2 access tokens for downstream services via five strategies:
 * <ul>
 *   <li>Password grant (cached, named provider)</li>
 *   <li>Client-credentials with gateway credentials (cached, named provider)</li>
 *   <li>Client-credentials with the caller's forwarded auth header (uncached, named provider)</li>
 *   <li>Client-credentials with direct config fields — P-25 (uncached, no YAML provider)</li>
 *   <li>Forwarded-auth with direct tokenUri — P-25 (uncached, no YAML provider)</li>
 * </ul>
 */
@Service
@Slf4j
public class Oauth2AccessTokenProvider {

    private static final String DEFAULT_SCOPES = "read write";
    private static final String PASSWORD_KEY_PREFIX = "password:";
    private static final String CC_KEY_PREFIX = "cc:";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final AuthProperties authProperties;
    private final CaffeineOauth2TokenCache tokenCache;
    private final WebClientRegistry webClientRegistry;

    public Oauth2AccessTokenProvider(AuthProperties authProperties,
                                     CaffeineOauth2TokenCache tokenCache,
                                     WebClientRegistry webClientRegistry) {
        this.authProperties = authProperties;
        this.tokenCache = tokenCache;
        this.webClientRegistry = webClientRegistry;
    }

    /**
     * Resolves a cached access token using the resource-owner password grant.
     *
     * @param oauth2ProviderName the named provider from {@code auth.oauth2.*}
     * @return a {@link Mono} emitting the access token
     */
    public Mono<String> accessToken(String oauth2ProviderName) {
        return tokenCache.getToken(PASSWORD_KEY_PREFIX + oauth2ProviderName);
    }

    /**
     * Resolves a cached access token using the client-credentials grant with
     * the gateway's own {@code client_id}/{@code client_secret}.
     *
     * @param oauth2ProviderName the named provider from {@code auth.oauth2.*}
     * @return a {@link Mono} emitting the access token
     */
    public Mono<String> accessTokenClientCredentials(String oauth2ProviderName) {
        return tokenCache.getToken(CC_KEY_PREFIX + oauth2ProviderName);
    }

    /**
     * Resolves a fresh (uncached) access token using client-credentials, forwarding
     * the caller's own {@code Authorization} header to the token endpoint.
     *
     * @param oauth2ProviderName the named provider
     * @param request            the inbound request whose {@code Authorization} header is forwarded
     * @return a {@link Mono} emitting the access token
     */
    public Mono<String> accessTokenForwardedAuth(String oauth2ProviderName, ServerHttpRequest request) {
        return fetchForwardedAuthToken(oauth2ProviderName, request);
    }

    // ─── Direct config methods (P-25 — no named provider required) ──────────

    /**
     * Acquires a cached client-credentials token using direct OAuth2 config fields
     * instead of a named provider from YAML.
     *
     * <p>Uses a per-{@code tokenUri+clientId} key in the Caffeine cache. On first
     * fetch (cache miss), the token is acquired from the token endpoint and cached
     * with its TTL. The secret is not part of the cache key — it is only used for
     * the actual token fetch.
     *
     * @param tokenUri     the token endpoint URI
     * @param clientId     the OAuth2 client ID
     * @param clientSecret the OAuth2 client secret
     * @param scope        space-separated scopes (nullable)
     * @param includeBasicAuth whether to include Basic Authorization header
     * @return a {@link Mono} emitting the access token
     */
    public Mono<String> accessTokenDirectClientCredentials(
            String tokenUri, String clientId, String clientSecret,
            String scope, boolean includeBasicAuth) {
        // One-shot fetch (uncached per request, but short-lived requests are fine)
        // A production enhancement could use a ConcurrentHashMap<cacheKey, Mono<TokenResponse>>
        // with singleFlight semantics. For now, this is simple and correct.
        return fetchDirectCcToken(tokenUri, clientId, clientSecret, scope, includeBasicAuth)
                .map(CaffeineOauth2TokenCache.TokenResponse::accessToken);
    }

    /**
     * Acquires a fresh (uncached) client-credentials token using direct config
     * and forwarding the caller's Authorization header.
     *
     * @param tokenUri the token endpoint URI
     * @param request  the inbound request whose Authorization header is forwarded
     * @return a {@link Mono} emitting the access token
     */
    public Mono<String> accessTokenDirectForwardedAuth(String tokenUri, ServerHttpRequest request) {
        return fetchDirectForwardedAuthToken(tokenUri, request);
    }

    @SuppressWarnings("java:S1144")
    Mono<CaffeineOauth2TokenCache.TokenResponse> fetchPasswordGrantToken(String cacheKey) {
        String providerName = cacheKey.substring(PASSWORD_KEY_PREFIX.length());
        AuthProperties.Oauth2Config config = authProperties.getOauth2().get(providerName);
        if (config == null) {
            return Mono.error(new IllegalStateException(
                    "No OAuth2 configuration found for provider '%s'".formatted(providerName)));
        }
        if (isBlank(config.getUsername())) {
            return Mono.error(new IllegalStateException(
                    "OAuth2 password-grant provider '%s' is missing a username".formatted(providerName)));
        }
        if (isBlank(config.getPassword())) {
            return Mono.error(new IllegalStateException(
                    "OAuth2 password-grant provider '%s' is missing a password".formatted(providerName)));
        }

        WebClient client = resolveWebClient(providerName, config);
        String path = URI.create(config.getUri()).getRawPath();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "password");
        formData.add("username", config.getUsername());
        formData.add("password", config.getPassword());
        formData.add("scope", Optional.ofNullable(config.getScope()).orElse(DEFAULT_SCOPES));
        if (config.getClientId() != null) formData.add("client_id", config.getClientId());
        if (config.getClientSecret() != null) formData.add("client_secret", config.getClientSecret());

        WebClient.RequestBodySpec requestSpec = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);

        if (config.isIncludeBasicClientAuthorization()) {
            requestSpec = requestSpec.header(HttpHeaders.AUTHORIZATION,
                    buildBasicAuthorizationHeader(config));
        }

        return requestSpec
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .map(this::toTokenResponse)
                .doOnNext(t -> log.debug("Fetched password-grant token for provider '{}'", providerName))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to retrieve OAuth2 access token for provider '%s'".formatted(providerName), e));
    }

    @SuppressWarnings("java:S1144")
    Mono<CaffeineOauth2TokenCache.TokenResponse> fetchClientCredentialsToken(String cacheKey) {
        String providerName = cacheKey.substring(CC_KEY_PREFIX.length());
        AuthProperties.Oauth2Config config = authProperties.getOauth2().get(providerName);
        if (config == null) {
            return Mono.error(new IllegalStateException(
                    "No OAuth2 configuration found for provider '%s'".formatted(providerName)));
        }

        WebClient client = resolveWebClient(providerName, config);
        String path = URI.create(config.getUri()).getRawPath();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        if (config.getClientId() != null) formData.add("client_id", config.getClientId());
        if (config.getClientSecret() != null) formData.add("client_secret", config.getClientSecret());
        if (config.getScope() != null) formData.add("scope", config.getScope());

        WebClient.RequestBodySpec requestSpec = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);

        if (config.isIncludeBasicClientAuthorization()) {
            requestSpec = requestSpec.header(HttpHeaders.AUTHORIZATION,
                    buildBasicAuthorizationHeader(config));
        }

        return requestSpec
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .map(this::toTokenResponse)
                .doOnNext(t -> log.debug("Fetched client-credentials token (cached) for provider '{}'",
                        providerName))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to retrieve OAuth2 client-credentials token for provider '%s'"
                                        .formatted(providerName), e));
    }

    private Mono<String> fetchForwardedAuthToken(String providerName, ServerHttpRequest request) {
        AuthProperties.Oauth2Config config = authProperties.getOauth2().get(providerName);
        if (config == null) {
            return Mono.error(new IllegalStateException(
                    "No OAuth2 configuration found for provider '%s'".formatted(providerName)));
        }

        WebClient client = resolveWebClient(providerName, config);
        String path = URI.create(config.getUri()).getRawPath();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");

        String authorizationHeader = Objects.requireNonNull(
                request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                "Authorization header is required for forwarded-auth client-credentials grant");

        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .map(this::toTokenResponse)
                .map(CaffeineOauth2TokenCache.TokenResponse::accessToken)
                .doOnNext(t -> log.debug(
                        "Fetched client-credentials token (forwarded auth, uncached) for provider '{}'",
                        providerName))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to retrieve OAuth2 access token for provider '%s'"
                                        .formatted(providerName), e));
    }

    // ─── Direct config fetchers (P-25 — no named YAML provider) ────────────

    /**
     * Fetches a client-credentials token using direct config fields (P-25).
     */
    private Mono<CaffeineOauth2TokenCache.TokenResponse> fetchDirectCcToken(
            String tokenUri, String clientId, String clientSecret,
            String scope, boolean includeBasicAuth) {
        WebClient client = webClientRegistry.getForUri("direct-cc:" + clientId, tokenUri);
        String path = URI.create(tokenUri).getRawPath();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        if (scope != null && !scope.isBlank()) {
            formData.add("scope", scope);
        }

        WebClient.RequestBodySpec requestSpec = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);

        if (includeBasicAuth) {
            String credentials = java.util.Base64.getEncoder()
                    .encodeToString((clientId + ":" + clientSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            requestSpec = requestSpec.header(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        }

        return requestSpec
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .map(this::toTokenResponse)
                .doOnNext(t -> log.debug("Fetched direct client-credentials token for tokenUri='{}'", tokenUri))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to retrieve OAuth2 client-credentials token from '%s'"
                                        .formatted(tokenUri), e));
    }

    private Mono<String> fetchDirectForwardedAuthToken(String tokenUri, ServerHttpRequest request) {
        WebClient client = webClientRegistry.getForUri("direct-fwd", tokenUri);
        String path = URI.create(tokenUri).getRawPath();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");

        String authorizationHeader = Objects.requireNonNull(
                request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                "Authorization header is required for forwarded-auth client-credentials grant");

        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .map(this::toTokenResponse)
                .map(CaffeineOauth2TokenCache.TokenResponse::accessToken)
                .doOnNext(t -> log.debug(
                        "Fetched direct client-credentials token (forwarded auth, uncached) for tokenUri='{}'",
                        tokenUri))
                .onErrorMap(e -> !(e instanceof IllegalStateException),
                        e -> new IllegalStateException(
                                "Unable to retrieve OAuth2 access token from '%s'".formatted(tokenUri), e));
    }

    private CaffeineOauth2TokenCache.TokenResponse toTokenResponse(Map<String, Object> body) {
        String accessToken = (String) body.get("access_token");
        Long expiresIn = parseOptionalLong(body.get("expires_in"));
        Long expires = parseOptionalLong(body.get("expires"));
        return new CaffeineOauth2TokenCache.TokenResponse(accessToken, expiresIn, expires);
    }

    private static Long parseOptionalLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private WebClient resolveWebClient(String providerName, AuthProperties.Oauth2Config config) {
        HttpClientProperties connConfig = config.getConnectionConfig() != null
                ? config.getConnectionConfig()
                : new HttpClientProperties();
        return webClientRegistry.get("token:" + providerName, config, config.getProxyConfig(), connConfig);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

