package io.routify.gateway.filter.auth;

import io.routify.common.crypto.FieldEncryptionService;
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
 * BasicAuth password matching tests for {@link BasicAuthGatewayFilterFactory}.
 *
 * <p>Covers three stored password formats:
 * <ul>
 *   <li>AES-encrypted ({@code {enc}} prefix) — current default</li>
 *   <li>Legacy BCrypt hash ({@code $2} prefix) — backward compatibility</li>
 *   <li>Legacy plain-text — fallback for very old configs</li>
 * </ul>
 *
 * <p>Also covers edge cases: missing/malformed headers, misconfigured filters,
 * header injection on success, and colons in passwords.
 */
class BasicAuthPasswordHashingTest {

    /** A valid 32-byte AES key for testing. */
    private static final String TEST_AES_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    private final FieldEncryptionService fieldEncryptionService = new FieldEncryptionService(TEST_AES_KEY);
    private final BasicAuthGatewayFilterFactory factory = new BasicAuthGatewayFilterFactory(fieldEncryptionService);

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

    private Config configWithEncrypted(String username, String rawPassword) {
        Config config = new Config();
        config.setUsername(username);
        config.setPassword(fieldEncryptionService.encrypt(rawPassword));
        return config;
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

    // ─── AES-encrypted password matching (current default) ────────────────────

    @Test
    @DisplayName("AES-encrypted password — correct credentials → authenticated")
    void aesEncryptedPassword_correctCredentials_passesThrough() {
        Config config = configWithEncrypted("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "secretP@ss"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("AES-encrypted password — wrong password → 401")
    void aesEncryptedPassword_wrongPassword_returns401() {
        Config config = configWithEncrypted("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "wrongPassword"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AES-encrypted password — wrong username → 401")
    void aesEncryptedPassword_wrongUsername_returns401() {
        Config config = configWithEncrypted("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("wronguser", "secretP@ss"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Legacy BCrypt password matching ──────────────────────────────────────

    @Test
    @DisplayName("Legacy BCrypt-hashed password — correct credentials → authenticated")
    void bcryptPassword_correctCredentials_passesThrough() {
        Config config = configWithBcrypt("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "secretP@ss"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Legacy BCrypt-hashed password — wrong password → 401")
    void bcryptPassword_wrongPassword_returns401() {
        Config config = configWithBcrypt("admin", "secretP@ss");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("admin", "wrongPassword"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Legacy plain-text fallback ───────────────────────────────────────────

    @Test
    @DisplayName("Legacy plain-text password — correct credentials → authenticated")
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
        Config config = configWithEncrypted("serviceUser", "myP@ss!");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("serviceUser", "myP@ss!"));

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
    @DisplayName("Missing Authorization header → 401")
    void missingAuthHeader_returns401() {
        Config config = configWithEncrypted("admin", "pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(null);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Non-Basic scheme (Bearer) → 401")
    void bearerScheme_returns401() {
        Config config = configWithEncrypted("admin", "pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth("Bearer some-jwt-token");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Malformed Base64 credentials → 401")
    void malformedBase64_returns401() {
        Config config = configWithEncrypted("admin", "pass");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth("Basic !!!not-valid-base64!!!");

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Misconfigured (blank credentials) ────────────────────────────────────

    @Test
    @DisplayName("Misconfigured — blank username → 401")
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
    @DisplayName("Misconfigured — null password → 401")
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
        Config config = configWithEncrypted("user", "pass:with:colons");
        GatewayFilter filter = factory.apply(config);

        var exchange = exchangeWithAuth(basicHeader("user", "pass:with:colons"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}

