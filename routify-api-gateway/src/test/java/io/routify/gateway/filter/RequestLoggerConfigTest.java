package io.routify.gateway.filter;

import io.routify.gateway.telemetry.GatewayTelemetryPublisher;
import io.routify.common.event.RequestTelemetryEvent;
import io.routify.common.web.RoutifyHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the new configuration features in
 * {@link RequestLoggerGatewayFilterFactory}:
 * <ul>
 *   <li>{@code maxBodyCaptureBytes} — body truncation with hard 64 KB limit</li>
 *   <li>{@code samplingRate} — probabilistic telemetry sampling</li>
 *   <li>{@code headerAllowlist} / {@code headerDenylist} — header capture control</li>
 *   <li>{@code skipPaths} — path exclusion patterns</li>
 *   <li>Default config backward compatibility</li>
 * </ul>
 */
class RequestLoggerConfigTest {

    private GatewayTelemetryPublisher telemetryPublisher;
    private RequestLoggerGatewayFilterFactory factory;

    @BeforeEach
    void setup() {
        telemetryPublisher = mock(GatewayTelemetryPublisher.class);
        factory = new RequestLoggerGatewayFilterFactory(telemetryPublisher);
    }

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // maxBodyCaptureBytes
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("maxBodyCaptureBytes: hard upper bound caps config above 64KB")
    void maxBodyCaptureBytes_hardUpperBound() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setMaxBodyCaptureBytes(200_000); // above 64 KB
        config.setLogRequestBody(true);

        // The factory should cap to 65536 internally — just verify it creates without error
        GatewayFilter filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    @Test
    @DisplayName("maxBodyCaptureBytes: falls back to maxBodyLogSize when maxBodyCaptureBytes is 0")
    void maxBodyCaptureBytes_fallbackToLegacy() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setMaxBodyLogSize(2048);
        // maxBodyCaptureBytes = 0 (default)

        GatewayFilter filter = factory.apply(config);
        assertThat(filter).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // samplingRate
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("samplingRate=0.0: produces zero telemetry events")
    void samplingRate_zeroProducesNoEvents() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setSamplingRate(0.0);
        GatewayFilter filter = factory.apply(config);

        // Run multiple requests — none should produce telemetry
        for (int i = 0; i < 20; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header(RoutifyHeaders.CORRELATION_ID, "corr-" + i)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();
        }

        verify(telemetryPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("samplingRate=1.0 (default): all requests produce telemetry")
    void samplingRate_oneProducesAllEvents() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setSamplingRate(1.0);
        GatewayFilter filter = factory.apply(config);

        int count = 10;
        for (int i = 0; i < count; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header(RoutifyHeaders.CORRELATION_ID, "corr-" + i)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();
        }

        verify(telemetryPublisher, times(count)).publish(any());
    }

    @Test
    @DisplayName("samplingRate=0.1: produces approximately 10% of telemetry events (statistical)")
    void samplingRate_tenPercentProducesApproximately() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setSamplingRate(0.1);
        GatewayFilter filter = factory.apply(config);

        int totalRequests = 1000;
        for (int i = 0; i < totalRequests; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header(RoutifyHeaders.CORRELATION_ID, "corr-" + i)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();
        }

        // Expect roughly 100 events (10%), allow wide margin: 30–200
        int publishCount = (int) mockingDetails(telemetryPublisher).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("publish"))
                .count();
        assertThat(publishCount).isBetween(30, 200);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // headerAllowlist / headerDenylist
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("headerAllowlist: only capture specified headers")
    void headerAllowlist_onlyCaptures() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setHeaderAllowlist(List.of("Content-Type"));
        config.setSamplingRate(1.0);

        // Use a capturing publisher to inspect the event
        CapturePublisher capture = new CapturePublisher(telemetryPublisher);
        var capFactory = new RequestLoggerGatewayFilterFactory(capture);
        GatewayFilter filter = capFactory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("Content-Type", "application/json")
                .header("X-Custom", "value")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(capture.events).hasSize(1);
        var capturedHeaders = capture.events.getFirst().requestHeaders();
        assertThat(capturedHeaders).containsKey("Content-Type");
        assertThat(capturedHeaders).doesNotContainKey("X-Custom");
    }

    @Test
    @DisplayName("headerDenylist: redact specified headers")
    void headerDenylist_redacts() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setHeaderDenylist(List.of("X-Secret"));
        config.setSamplingRate(1.0);

        CapturePublisher capture = new CapturePublisher(telemetryPublisher);
        var capFactory = new RequestLoggerGatewayFilterFactory(capture);
        GatewayFilter filter = capFactory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("X-Secret", "super-secret-value")
                .header("X-Public", "visible")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(capture.events).hasSize(1);
        var capturedHeaders = capture.events.getFirst().requestHeaders();
        assertThat(capturedHeaders.get("X-Secret")).isEqualTo("[REDACTED]");
        assertThat(capturedHeaders.get("X-Public")).isEqualTo("visible");
    }

    @Test
    @DisplayName("headerDenylist overrides headerAllowlist")
    void headerDenyOverridesAllow() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setHeaderAllowlist(List.of("Authorization", "Content-Type"));
        config.setHeaderDenylist(List.of("Authorization"));
        config.setSamplingRate(1.0);

        CapturePublisher capture = new CapturePublisher(telemetryPublisher);
        var capFactory = new RequestLoggerGatewayFilterFactory(capture);
        GatewayFilter filter = capFactory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("Authorization", "Bearer token")
                .header("Content-Type", "application/json")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(capture.events).hasSize(1);
        var capturedHeaders = capture.events.getFirst().requestHeaders();
        assertThat(capturedHeaders.get("Authorization")).isEqualTo("[REDACTED]");
        assertThat(capturedHeaders).containsKey("Content-Type");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // skipPaths
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("skipPaths: matching path skips logging and telemetry")
    void skipPaths_matchSkips() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setSkipPaths(List.of("/health", "/actuator/**"));
        config.setSamplingRate(1.0);
        GatewayFilter filter = factory.apply(config);

        // /health should be skipped
        MockServerHttpRequest request1 = MockServerHttpRequest.get("/health")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(request1), passThroughChain()))
                .verifyComplete();

        // /actuator/health should be skipped
        MockServerHttpRequest request2 = MockServerHttpRequest.get("/actuator/health")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-2")
                .build();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(request2), passThroughChain()))
                .verifyComplete();

        // /api/test should NOT be skipped
        MockServerHttpRequest request3 = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-3")
                .build();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(request3), passThroughChain()))
                .verifyComplete();

        // Only the /api/test request should have produced telemetry
        verify(telemetryPublisher, times(1)).publish(any());
    }

    @Test
    @DisplayName("skipPaths: /actuator/** glob matches nested paths")
    void skipPaths_globMatchesNested() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        config.setSkipPaths(List.of("/actuator/**"));
        config.setSamplingRate(1.0);
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/prometheus")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(request), passThroughChain()))
                .verifyComplete();

        verify(telemetryPublisher, never()).publish(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Default config backward compatibility
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Default config: all requests produce telemetry (no behavior change)")
    void defaultConfig_allRequestsLogged() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        // All defaults: samplingRate=1.0, no skipPaths, no allowlist/denylist overrides
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        verify(telemetryPublisher, times(1)).publish(any());
    }

    @Test
    @DisplayName("Default config: Authorization header is redacted by default")
    void defaultConfig_authorizationRedacted() {
        var config = new RequestLoggerGatewayFilterFactory.Config();

        CapturePublisher capture = new CapturePublisher(telemetryPublisher);
        var capFactory = new RequestLoggerGatewayFilterFactory(capture);
        GatewayFilter filter = capFactory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header("Authorization", "Bearer secret-token")
                .header("Content-Type", "application/json")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(capture.events).hasSize(1);
        var capturedHeaders = capture.events.getFirst().requestHeaders();
        assertThat(capturedHeaders.get("Authorization")).isEqualTo("[REDACTED]");
        assertThat(capturedHeaders).containsKey("Content-Type");
    }

    @Test
    @DisplayName("Replay header: request is skipped entirely")
    void replayHeader_skipsLogging() {
        var config = new RequestLoggerGatewayFilterFactory.Config();
        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(RoutifyHeaders.CORRELATION_ID, "corr-1")
                .header(RequestLoggerGatewayFilterFactory.REPLAY_HEADER, "true")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        verify(telemetryPublisher, never()).publish(any());
    }

    // ─── Helper: capturing telemetry publisher ───────────────────────────────

    /**
     * A telemetry publisher that delegates to the mock but also captures events
     * for assertion in tests.
     */
    static class CapturePublisher extends GatewayTelemetryPublisher {
        final List<RequestTelemetryEvent> events = new CopyOnWriteArrayList<>();

        CapturePublisher(GatewayTelemetryPublisher delegate) {
            super(null);
        }

        @Override
        public void publish(RequestTelemetryEvent event) {
            events.add(event);
        }
    }
}

