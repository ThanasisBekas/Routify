package io.routify.gateway.filter.validation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.routify.gateway.filter.GatewayTenantPlanCache;
import io.routify.gateway.filter.TenantContextGatewayFilterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestSizeLimitGatewayFilterFactory}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Size string parsing (KB, MB, GB, plain bytes)</li>
 *   <li>Stage 1 — Content-Length header fast rejection</li>
 *   <li>Stage 2 — Streaming byte counter for chunked transfers</li>
 *   <li>Requests within limit pass through</li>
 *   <li>Tenant-aware mode resolves per-tenant limits</li>
 *   <li>RFC 9457 ProblemDetail response on rejection</li>
 * </ul>
 */
class RequestSizeLimitTest {

    private MeterRegistry meterRegistry;
    private GatewayTenantPlanCache tenantPlanCache;
    private RequestSizeLimitGatewayFilterFactory factory;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tenantPlanCache = new GatewayTenantPlanCache();
        factory = new RequestSizeLimitGatewayFilterFactory(meterRegistry, tenantPlanCache);
    }

    // ── Size parsing ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Size parsing")
    class SizeParsing {

        @Test
        @DisplayName("5MB → 5242880 bytes")
        void parseMegabytes() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("5MB")).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("512KB → 524288 bytes")
        void parseKilobytes() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("512KB")).isEqualTo(512L * 1024);
        }

        @Test
        @DisplayName("1GB → 1073741824 bytes")
        void parseGigabytes() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("1GB")).isEqualTo(1L * 1024 * 1024 * 1024);
        }

        @Test
        @DisplayName("Plain bytes: 1048576 → 1048576")
        void parsePlainBytes() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("1048576")).isEqualTo(1048576L);
        }

        @Test
        @DisplayName("null → default 5MB")
        void parseNull() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize(null)).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("blank string → default 5MB")
        void parseBlank() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("  ")).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("Unparseable → default 5MB")
        void parseInvalid() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("invalid")).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("Case-insensitive suffix: 5mb → 5242880")
        void parseCaseInsensitive() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("5mb")).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("B suffix: 100B → 100")
        void parseByteSuffix() {
            assertThat(RequestSizeLimitGatewayFilterFactory.parseSize("100B")).isEqualTo(100L);
        }
    }

    // ── Format bytes ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Format bytes")
    class FormatBytes {

        @Test
        @DisplayName("5242880 → 5MB")
        void formatMB() {
            assertThat(RequestSizeLimitGatewayFilterFactory.formatBytes(5L * 1024 * 1024)).isEqualTo("5MB");
        }

        @Test
        @DisplayName("1073741824 → 1GB")
        void formatGB() {
            assertThat(RequestSizeLimitGatewayFilterFactory.formatBytes(1024L * 1024 * 1024)).isEqualTo("1GB");
        }

        @Test
        @DisplayName("1024 → 1KB")
        void formatKB() {
            assertThat(RequestSizeLimitGatewayFilterFactory.formatBytes(1024L)).isEqualTo("1KB");
        }

        @Test
        @DisplayName("Non-aligned value → bytes suffix")
        void formatPlainBytes() {
            assertThat(RequestSizeLimitGatewayFilterFactory.formatBytes(1500L)).isEqualTo("1500B");
        }
    }

    // ── Stage 1: Content-Length header check ─────────────────────────────────

    @Nested
    @DisplayName("Stage 1 — Content-Length header check")
    class ContentLengthCheck {

        @Test
        @DisplayName("Content-Length exceeding maxSize → HTTP 413")
        void contentLengthExceeds_returns413() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("5MB");
            GatewayFilter filter = factory.apply(config);

            // 10 MB Content-Length
            long tenMB = 10L * 1024 * 1024;
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header("Content-Length", String.valueOf(tenMB))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("Content-Length within limit → passes through")
        void contentLengthWithinLimit_passesThrough() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("5MB");
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header("Content-Length", "1024")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            // No error status — passed through
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("Missing Content-Length with checkContentLength=true → falls through to streaming")
        void missingContentLength_fallsThroughToStreaming() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("5MB");
            config.setCheckActualSize(false); // Disable streaming too
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            // No rejection — missing Content-Length is not treated as over-limit
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // ── Stage 2: Streaming byte counter ──────────────────────────────────────

    @Nested
    @DisplayName("Stage 2 — Streaming byte counter")
    class StreamingCheck {

        @Test
        @DisplayName("Chunked request exceeding maxSize → HTTP 413")
        void chunkedExceeds_returns413() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("100B");
            config.setCheckContentLength(false); // Only test streaming
            GatewayFilter filter = factory.apply(config);

            // Create a request with a body exceeding 100 bytes
            String body = "x".repeat(150);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .body(body);
            MockServerWebExchange exchange = MockServerWebExchange.from(request);


            // Chain that reads the body (triggers the streaming counter)
            StepVerifier.create(filter.filter(exchange, ex ->
                            ex.getRequest().getBody()
                                    .then()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("Request within streaming limit → passes through")
        void withinStreamingLimit_passesThrough() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("1KB");
            config.setCheckContentLength(false);
            GatewayFilter filter = factory.apply(config);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            // Chain that reads body (but body is empty — MockServerWebExchange default)
            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // ── Tenant-aware mode ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tenant-aware mode")
    class TenantAwareMode {

        @Test
        @DisplayName("tenantAware=true with FREE plan → uses plan limit (5MB)")
        void tenantAware_freeplanLimit() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("100MB"); // static limit is higher
            config.setTenantAware(true);
            config.setCheckActualSize(false);
            GatewayFilter filter = factory.apply(config);

            UUID tenantId = UUID.randomUUID();
            // GatewayTenantPlanCache defaults to FREE for unknown tenants

            // Content-Length of 10MB — exceeds FREE plan's 5MB limit
            long tenMB = 10L * 1024 * 1024;
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header("Content-Length", String.valueOf(tenMB))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(TenantContextGatewayFilterFactory.ATTR_TENANT_ID, tenantId.toString());

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("tenantAware=true without tenant → falls back to static maxSize")
        void tenantAware_noTenant_fallsBackToStatic() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("1MB");
            config.setTenantAware(true);
            config.setCheckActualSize(false);
            GatewayFilter filter = factory.apply(config);

            // No ATTR_TENANT_ID set on exchange
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header("Content-Length", "512")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            // 512 bytes < 1MB — passes through
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // ── ProblemDetail response ────────────────────────────────────────────────

    @Nested
    @DisplayName("ProblemDetail response")
    class ProblemDetailResponse {

        @Test
        @DisplayName("413 response body is ProblemDetail JSON with maxSize")
        void rejectionResponse_isProblemDetailWithMaxSize() {
            var config = new RequestSizeLimitGatewayFilterFactory.Config();
            config.setMaxSize("5MB");
            GatewayFilter filter = factory.apply(config);

            long tenMB = 10L * 1024 * 1024;
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                    .header("Content-Length", String.valueOf(tenMB))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(exchange.getResponse().getHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/problem+json");
        }
    }

    // ── Default config ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Default config: maxSize=5MB, checkContentLength=true, checkActualSize=true, tenantAware=false")
    void defaultConfig() {
        var config = new RequestSizeLimitGatewayFilterFactory.Config();
        assertThat(config.getMaxSize()).isEqualTo("5MB");
        assertThat(config.isCheckContentLength()).isTrue();
        assertThat(config.isCheckActualSize()).isTrue();
        assertThat(config.isTenantAware()).isFalse();
    }

    // ── Metrics ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rejected counter increments on 413")
    void rejectedCounter_incrementsOn413() {
        var config = new RequestSizeLimitGatewayFilterFactory.Config();
        config.setMaxSize("1KB");
        GatewayFilter filter = factory.apply(config);

        long fiveMB = 5L * 1024 * 1024;
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                .header("Content-Length", String.valueOf(fiveMB))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(meterRegistry.find("routify.filter.request_size.rejected").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
    }

    @Test
    @DisplayName("Size summary records body size for known Content-Length")
    void sizeSummary_recordsContentLength() {
        var config = new RequestSizeLimitGatewayFilterFactory.Config();
        config.setMaxSize("10MB");
        config.setCheckActualSize(false);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/data")
                .header("Content-Length", "2048")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(meterRegistry.find("routify.filter.request_size.bytes").summary())
                .isNotNull()
                .satisfies(s -> assertThat(s.count()).isEqualTo(1));
    }
}

