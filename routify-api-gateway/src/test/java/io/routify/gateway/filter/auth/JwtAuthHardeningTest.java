package io.routify.gateway.filter.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.routify.gateway.filter.JwtAuthGatewayFilterFactory;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GF-04 JwtAuth Hardening — dedicated tests for JWKS URI support,
 * key rotation, misconfiguration detection, issuer/audience enforcement,
 * require-jti behaviour, and HS256 migration warning.
 *
 * <p>Uses OkHttp {@link MockWebServer} (v5.x) to simulate a JWKS endpoint.
 */
class JwtAuthHardeningTest {

    private static PrivateKey privateKey;
    private static PublicKey publicKey;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer jwksServer;
    private ReactiveStringRedisTemplate redisTemplate;

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
        jwksServer = new MockWebServer();
        jwksServer.start();
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        jwksServer.close();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private JwtAuthGatewayFilterFactory createFactory(String publicKeyBase64, String jwksUri,
                                                       boolean requireJti) throws Exception {
        var factory = new JwtAuthGatewayFilterFactory(
                redisTemplate, WebClient.builder(), MAPPER);
        setField(factory, "publicKeyBase64", publicKeyBase64 != null ? publicKeyBase64 : "");
        setField(factory, "jwksUri", jwksUri != null ? jwksUri : "");
        setField(factory, "jwksCacheMinutes", 5);
        setField(factory, "globalRequireJti", requireJti);
        factory.validateKeySource();
        return factory;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        var field = JwtAuthGatewayFilterFactory.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String issueTokenWithKid(UUID userId, String tenantId, String role, String jti,
                                      Instant expiry, String kid) {
        var builder = Jwts.builder()
                .header().add("kid", kid).and()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .claim("role", role)
                .claim("email", "test@test.com")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .signWith(privateKey);
        if (jti != null) builder.id(jti);
        return builder.compact();
    }

    private String issueToken(UUID userId, String tenantId, String role, String jti,
                               Instant expiry) {
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId)
                .claim("role", role)
                .claim("email", "test@test.com")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .signWith(privateKey);
        if (jti != null) builder.id(jti);
        return builder.compact();
    }

    private String issueTokenWithIssuerAndAudience(String jti, String issuer, String audience) {
        var builder = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("tenantId", "t1")
                .claim("role", "VIEWER")
                .claim("email", "test@test.com")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(privateKey);
        if (jti != null) builder.id(jti);
        if (issuer != null) builder.issuer(issuer);
        if (audience != null) builder.audience().add(audience).and();
        return builder.compact();
    }

    /**
     * Builds a JWKS JSON response containing the test RSA public key.
     */
    private String buildJwksResponse(String kid) throws Exception {
        RSAPublicKey rsaPk = (RSAPublicKey) publicKey;
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode keys = root.putArray("keys");
        ObjectNode keyNode = keys.addObject();
        keyNode.put("kty", "RSA");
        keyNode.put("use", "sig");
        keyNode.put("alg", "RS256");
        if (kid != null) keyNode.put("kid", kid);
        keyNode.put("n", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedBytes(rsaPk.getModulus())));
        keyNode.put("e", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedBytes(rsaPk.getPublicExponent())));
        return MAPPER.writeValueAsString(root);
    }

    private byte[] toUnsignedBytes(BigInteger val) {
        byte[] bytes = val.toByteArray();
        // Strip leading zero byte if present (BigInteger sign bit)
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private MockResponse jwksJsonResponse(String body) {
        return new MockResponse.Builder()
                .body(body)
                .addHeader("Content-Type", "application/json")
                .build();
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ─── JWKS URI Tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("JWKS URI configured — keys fetched and cached, JWT verified with kid")
    void jwksUri_fetchesKeyAndVerifiesJwt() throws Exception {
        String kid = "test-key-1";
        jwksServer.enqueue(jwksJsonResponse(buildJwksResponse(kid)));

        String jwksUrl = jwksServer.url("/.well-known/jwks.json").toString();
        var factory = createFactory(null, jwksUrl, true);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        String jti = UUID.randomUUID().toString();
        String token = issueTokenWithKid(UUID.randomUUID(), "t1", "OPERATOR", jti,
                Instant.now().plusSeconds(3600), kid);

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        var request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        var exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        verify(redisTemplate).hasKey("routify:token:blocklist:" + jti);
        assertThat(jwksServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("JWKS key rotation — second request with same kid uses cache (no re-fetch)")
    void jwksUri_cachesPreviouslyFetchedKey() throws Exception {
        String kid = "test-key-1";
        jwksServer.enqueue(jwksJsonResponse(buildJwksResponse(kid)));

        String jwksUrl = jwksServer.url("/.well-known/jwks.json").toString();
        var factory = createFactory(null, jwksUrl, true);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        // First request — fetches from JWKS
        String jti1 = UUID.randomUUID().toString();
        String token1 = issueTokenWithKid(UUID.randomUUID(), "t1", "VIEWER", jti1,
                Instant.now().plusSeconds(3600), kid);
        var exchange1 = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1).build());
        StepVerifier.create(filter.filter(exchange1, passThroughChain())).verifyComplete();

        // Second request — should use cached key
        String jti2 = UUID.randomUUID().toString();
        String token2 = issueTokenWithKid(UUID.randomUUID(), "t1", "VIEWER", jti2,
                Instant.now().plusSeconds(3600), kid);
        var exchange2 = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token2).build());
        StepVerifier.create(filter.filter(exchange2, passThroughChain())).verifyComplete();

        // Only one HTTP request to JWKS endpoint
        assertThat(jwksServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("JWKS fetch failure + static key fallback — JWT verified via static key")
    void jwksFetchFailure_fallsBackToStaticKey() throws Exception {
        // JWKS returns error
        jwksServer.enqueue(new MockResponse.Builder().code(500).body("Internal Server Error").build());

        String jwksUrl = jwksServer.url("/.well-known/jwks.json").toString();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var factory = createFactory(publicKeyBase64, jwksUrl, true);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        String jti = UUID.randomUUID().toString();
        // Token WITHOUT kid — will attempt JWKS (default kid), fail, then fallback to static
        String token = issueToken(UUID.randomUUID(), "t1", "VIEWER", jti,
                Instant.now().plusSeconds(3600));

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        var request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
        var exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Verify JWT was accepted (Redis was called)
        verify(redisTemplate).hasKey("routify:token:blocklist:" + jti);
    }

    // ─── Misconfigured (no key source) ────────────────────────────────────────

    @Test
    @DisplayName("Neither public key nor JWKS URI → 500 SERVER_MISCONFIGURED on every request")
    void misconfigured_rejectsAllRequests() throws Exception {
        var factory = createFactory("", "", true);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        String token = issueToken(UUID.randomUUID(), "t1", "VIEWER",
                UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));

        var request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
        var exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verifyNoInteractions(redisTemplate);
    }

    // ─── Issuer / Audience Validation ─────────────────────────────────────────

    @Test
    @DisplayName("Issuer mismatch → 401 INVALID_ISSUER")
    void issuerMismatch_returns401() throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var factory = createFactory(publicKeyBase64, null, true);
        var config = new JwtAuthGatewayFilterFactory.Config();
        config.setIssuer("https://auth.example.com");
        var filter = factory.apply(config);

        String token = issueTokenWithIssuerAndAudience(
                UUID.randomUUID().toString(), "https://wrong.com", null);

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        StepVerifier.create(filter.filter(exchange, passThroughChain())).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Audience mismatch → 401 INVALID_AUDIENCE")
    void audienceMismatch_returns401() throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var factory = createFactory(publicKeyBase64, null, true);
        var config = new JwtAuthGatewayFilterFactory.Config();
        config.setAudience("api://routify");
        var filter = factory.apply(config);

        String token = issueTokenWithIssuerAndAudience(
                UUID.randomUUID().toString(), null, "api://other-service");

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        StepVerifier.create(filter.filter(exchange, passThroughChain())).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Require JTI ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing JTI + require-jti=true → 401 MISSING_JTI")
    void missingJti_requireTrue_returns401() throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var factory = createFactory(publicKeyBase64, null, true);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        String token = issueToken(UUID.randomUUID(), "t1", "VIEWER", null,
                Instant.now().plusSeconds(3600));

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        StepVerifier.create(filter.filter(exchange, passThroughChain())).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Missing JTI + require-jti=false → passes through")
    void missingJti_requireFalse_passesThrough() throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var factory = createFactory(publicKeyBase64, null, false);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        String token = issueToken(UUID.randomUUID(), "t1", "VIEWER", null,
                Instant.now().plusSeconds(3600));

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        StepVerifier.create(filter.filter(exchange, passThroughChain())).verifyComplete();
        // Should pass — no Redis call (no JTI)
        verifyNoInteractions(redisTemplate);
    }

    // ─── HS256 Migration Warning ──────────────────────────────────────────────

    @Test
    @DisplayName("HS256 algorithm config → treated as RS256 (still validates)")
    void hs256Config_treatedAsRs256() throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        var factory = createFactory(publicKeyBase64, null, true);
        var config = new JwtAuthGatewayFilterFactory.Config();
        config.setAlgorithm("HS256"); // deprecated, should be ignored
        var filter = factory.apply(config);

        String jti = UUID.randomUUID().toString();
        String token = issueToken(UUID.randomUUID(), "t1", "VIEWER", jti,
                Instant.now().plusSeconds(3600));

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        StepVerifier.create(filter.filter(exchange, passThroughChain())).verifyComplete();

        // Token was still verified with RS256 (the HS256 config is ignored)
        verify(redisTemplate).hasKey("routify:token:blocklist:" + jti);
    }

    // ─── JWKS without kid in token ────────────────────────────────────────────

    @Test
    @DisplayName("JWKS URI + token without kid → uses first RSA key from JWKS")
    void jwksUri_tokenWithoutKid_usesFirstKey() throws Exception {
        jwksServer.enqueue(jwksJsonResponse(buildJwksResponse("default-kid")));

        String jwksUrl = jwksServer.url("/.well-known/jwks.json").toString();
        var factory = createFactory(null, jwksUrl, true);
        var filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        String jti = UUID.randomUUID().toString();
        // Token without kid header
        String token = issueToken(UUID.randomUUID(), "t1", "VIEWER", jti,
                Instant.now().plusSeconds(3600));

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        StepVerifier.create(filter.filter(exchange, passThroughChain())).verifyComplete();
        verify(redisTemplate).hasKey("routify:token:blocklist:" + jti);
    }
}

