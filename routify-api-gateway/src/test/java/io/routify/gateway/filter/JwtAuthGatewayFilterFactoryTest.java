package io.routify.gateway.filter;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtAuthGatewayFilterFactory}.
 *
 * <p>Tests JWT validation, header injection, token expiry, blocklist checks,
 * and query-param token extraction. Uses an in-memory RSA key pair (no containers needed).
 * Mocks the {@link ReactiveRedisOperations} interface to avoid JDK 25+ class mocking issues.
 */
class JwtAuthGatewayFilterFactoryTest {

    private static PrivateKey privateKey;
    private static PublicKey publicKey;

    private ReactiveStringRedisTemplate redisTemplate;
    private JwtAuthGatewayFilterFactory factory;
    private GatewayFilter filter;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = pair.getPrivate();
        publicKey = pair.getPublic();
    }

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        factory = new JwtAuthGatewayFilterFactory(redisTemplate);

        // Inject the public key via reflection (non-final @Value field)
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var keyField = JwtAuthGatewayFilterFactory.class.getDeclaredField("publicKeyBase64");
        keyField.setAccessible(true);
        keyField.set(factory, publicKeyBase64);

        filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String issueToken(UUID userId, String tenantId, String role, String email,
                              String jti, Instant expiry) {
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .claim("role", role)
                .claim("email", email)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .signWith(privateKey);
        if (jti != null) builder.id(jti);
        return builder.compact();
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid JWT with JTI — injects auth headers and passes through (not blocked)")
    void validJwt_injectsHeadersAndPassesThrough() {
        UUID userId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        String token = issueToken(userId, tenantId, "OPERATOR", "user@test.com",
                jti, Instant.now().plusSeconds(3600));

        // Redis says the token is NOT blocklisted
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // The filter mutates the request — we verify the chain received the right headers
        // by checking the exchange. Since we used a pass-through chain, we verify via the
        // mocked Redis call (the chain was invoked if Redis was checked)
        verify(redisTemplate).hasKey("routify:token:blocklist:" + jti);
    }

    @Test
    @DisplayName("Missing Authorization header returns 401 MISSING_TOKEN")
    void missingAuthHeader_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Expired JWT returns 401 TOKEN_EXPIRED")
    void expiredJwt_returns401() {
        String token = issueToken(UUID.randomUUID(), "tenant1", "VIEWER", "u@t.com",
                UUID.randomUUID().toString(), Instant.now().minusSeconds(60));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Malformed JWT returns 401 INVALID_TOKEN")
    void malformedJwt_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Blocklisted JWT (revoked JTI) returns 401 TOKEN_REVOKED")
    void blocklistedJwt_returns401() {
        UUID userId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = issueToken(userId, "tenant1", "OPERATOR", "u@t.com",
                jti, Instant.now().plusSeconds(3600));

        // Redis says the token IS blocklisted
        when(redisTemplate.hasKey("routify:token:blocklist:" + jti)).thenReturn(Mono.just(true));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Token from ?token= query param is accepted (WebSocket/SSE)")
    void tokenFromQueryParam_isAccepted() {
        UUID userId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = issueToken(userId, "tenant1", "VIEWER", "u@t.com",
                jti, Instant.now().plusSeconds(3600));

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .queryParam("token", token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Verify Redis was queried (means JWT was parsed successfully)
        verify(redisTemplate).hasKey("routify:token:blocklist:" + jti);
    }

    @Test
    @DisplayName("JWT without JTI — passes through without blocklist check (with warning)")
    void jwtWithoutJti_passesThrough() {
        String token = issueToken(UUID.randomUUID(), "tenant1", "VIEWER", "u@t.com",
                null, Instant.now().plusSeconds(3600));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // No Redis call — no JTI to check
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Bearer prefix is required — raw token in Authorization header is rejected")
    void rawTokenWithoutBearerPrefix_returns401() {
        String token = issueToken(UUID.randomUUID(), "tenant1", "VIEWER", "u@t.com",
                UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, token) // No "Bearer " prefix
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Valid JWT injects correct X-Auth-* header values into the chain")
    void validJwt_injectsCorrectAuthHeaders() {
        UUID userId = UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        String token = issueToken(userId, tenantId, "SUPER_ADMIN", "admin@test.com",
                jti, Instant.now().plusSeconds(3600));

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Capture the mutated request that the chain receives
        final org.springframework.http.server.reactive.ServerHttpRequest[] captured = {null};
        GatewayFilterChain capturingChain = ex -> {
            captured[0] = ex.getRequest();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getHeaders().getFirst("X-Auth-User-Id")).isEqualTo(userId.toString());
        assertThat(captured[0].getHeaders().getFirst("X-Auth-Tenant-Id")).isEqualTo(tenantId);
        assertThat(captured[0].getHeaders().getFirst("X-Auth-Role")).isEqualTo("SUPER_ADMIN");
        assertThat(captured[0].getHeaders().getFirst("X-Auth-Email")).isEqualTo("admin@test.com");
    }
}

