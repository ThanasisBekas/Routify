package io.routify.gateway.filter.auth;

import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.filter.BasicAuthGatewayFilterFactory;
import io.routify.gateway.filter.BasicAuthGatewayFilterFactory.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-03 BasicAuth Password Hashing — dedicated tests for BCrypt password
 * comparison in {@link BasicAuthGatewayFilterFactory}, plain-text legacy
 * fallback, and edge cases.
 *
 * <p>Covers:
 * <ul>
 *   <li>BCrypt-hashed password matches correctly</li>
 *   <li>BCrypt-hashed password rejects wrong credentials</li>
 *   <li>Legacy plain-text password still works (backward compatibility)</li>
 *   <li>Missing Authorization header → 401</li>
 *   <li>Malformed Base64 → 401</li>
 *   <li>Misconfigured (blank username/password) → 401</li>
 *   <li>Successful auth injects X-Auth-User-Id and X-Auth-Type headers</li>
 *   <li>Non-Basic scheme → 401</li>
 * </ul>
 */
class BasicAuthPasswordHashingTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    private final BasicAuthGatewayFilterFactory factory = new BasicAuthGatewayFilterFactory();

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    private String basicHeader(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    private MockServerWebExchange exchangeWithAuth(String authHeaderValue) {
        var builder = MockServerHttpRequest.get("/api/test");
        if (authHeaderValue != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authHeaderValue);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private Config configWithBcrypt(String username, String rawPassword) {
        Config config = new Config();
        config.setUsername(username);
        config.setPassword(BCRYPT.encode(rawPassword));
        return config;
    }

    private Config configWithPlainText(String username, String password) {
        Config config = new Config();
        config.setUsername(username);
        config.setPassword(password);
        return config;
    }

    // ─── BCrypt password matching ─────────────────────────────────────────────

    @Test
    @DisplayName("BCrypt-hashed password — correct credentials → authenticated, headers injected")
    void bcryptPassword_correctCredentials_passesThrough() {
        Config config = configWithBcrypt("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "secretP@ss"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        // Should pass through (no error status set)
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("BCrypt-hashed password — wrong password → 401 INVALID_CREDENTIALS")
    void bcryptPassword_wrongPassword_returns401() {
        Config config = configWithBcrypt("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "wrongPassword"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BCrypt-hashed password — wrong username → 401 INVALID_CREDENTIALS")
    void bcryptPassword_wrongUsername_returns401() {
        Config config = configWithBcrypt("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("wronguser", "secretP@ss"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Legacy plain-text fallback ───────────────────────────────────────────

    @Test
    @DisplayName("Legacy plain-text password — correct credentials → authenticated (backward compat)")
    void plainTextPassword_correctCredentials_passesThrough() {
        Config config = configWithPlainText("operator", "plain123");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("operator", "plain123"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Legacy plain-text password — wrong credentials → 401")
    void plainTextPassword_wrongCredentials_returns401() {
        Config config = configWithPlainText("operator", "plain123");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("operator", "wrong"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Header injection on success ──────────────────────────────────────────

    @Test
    @DisplayName("Successful auth injects X-Auth-User-Id and X-Auth-Type: BASIC")
    void successfulAuth_injectsHeaders() {
        Config config = configWithBcrypt("serviceUser", "myP@ss!");
        GatewayFilter filter = factory.apply(config);

        // We need a chain that captures the mutated exchange to verify headers
        var exchange = exchangeWithAuth(basicHeader("serviceUser", "myP@ss!"));

        // Use a chain that captures the request headers for verification
        final String[] capturedUserId = {null};
        final String[] capturedAuthType = {null};
        GatewayFilterChain capturingChain = ex -> {
            capturedUserId[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);
            capturedAuthType[0] = ex.getRequest().getHeaders().getFirst(RoutifyHeaders.AUTH_TYPE);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        assertThat(capturedUserId[0]).isEqualTo("serviceUser");
        assertThat(capturedAuthType[0]).isEqualTo("BASIC");
    }

    // ─── Missing / malformed credentials ──────────────────────────────────────

    @Test
    @DisplayName("Missing Authorization header → 401 MISSING_CREDENTIALS")
    void missingAuthHeader_returns401() {
        Config config = configWithBcrypt("admin", "pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(null);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Non-Basic scheme (Bearer) → 401 MISSING_CREDENTIALS")
    void bearerScheme_returns401() {
        Config config = configWithBcrypt("admin", "pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth("Bearer some-jwt-token");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Malformed Base64 credentials → 401 INVALID_CREDENTIALS")
    void malformedBase64_returns401() {
        Config config = configWithBcrypt("admin", "pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth("Basic !!!not-valid-base64!!!");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Misconfigured (blank credentials) ────────────────────────────────────

    @Test
    @DisplayName("Misconfigured — blank username → 401 GATEWAY_MISCONFIGURATION")
    void blankUsername_returns401() {
        Config config = new Config();
        config.setUsername("");
        config.setPassword("somePass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "somePass"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Misconfigured — null password → 401 GATEWAY_MISCONFIGURATION")
    void nullPassword_returns401() {
        Config config = new Config();
        config.setUsername("admin");
        config.setPassword(null);
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "test"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Base64 with colon in password ────────────────────────────────────────

    @Test
    @DisplayName("Password containing colon character → correctly decoded and matched")
    void passwordWithColon_correctlyDecoded() {
        Config config = configWithBcrypt("user", "pass:with:colons");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("user", "pass:with:colons"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}

