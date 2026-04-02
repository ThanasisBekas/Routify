package gr.routify.gateway.filter;

import gr.routify.common.web.RoutifyHeaders;
import gr.routify.gateway.telemetry.GatewayTelemetryPublisher;
import gr.routify.gateway.telemetry.GatewayTelemetryPublisher.TelemetryEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Gateway filter factory that logs every request on entry and publishes rich telemetry
 * to the {@code routify.request.telemetry} Kafka topic on completion.
 *
 * <p>Every request is captured, regardless of outcome:
 * <ul>
 *   <li>Successful responses (2xx/3xx)</li>
 *   <li>Client errors (4xx)</li>
 *   <li>Server errors (5xx) — marked as {@code failed=true} for replay</li>
 *   <li>Exceptions / circuit-open — marked as {@code failed=true} for replay</li>
 * </ul>
 *
 * <p>Filter type: {@code REQUEST_LOGGER}
 */
@Component
@Slf4j
public class RequestLoggerGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestLoggerGatewayFilterFactory.Config> {

    /** Header names to suppress from captured request/response headers (security). */
    private static final List<String> REDACTED_HEADERS = List.of(
            "Authorization", RoutifyHeaders.API_KEY, "Cookie", "Set-Cookie", RoutifyHeaders.AUTH_TOKEN,
            RoutifyHeaders.REPLAY_MARKER
    );

    /**
     * Internal header set by the replay service to prevent double-logging.
     * When present, the REQUEST_LOGGER skips telemetry publishing and strips
     * the header before forwarding to the upstream.
     */
    public static final String REPLAY_HEADER = RoutifyHeaders.REPLAY_MARKER;

    private final GatewayTelemetryPublisher telemetryPublisher;

    public RequestLoggerGatewayFilterFactory(GatewayTelemetryPublisher telemetryPublisher) {
        super(Config.class);
        this.telemetryPublisher = telemetryPublisher;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new RequestLoggerGatewayFilter(telemetryPublisher, config);
    }

    /**
     * Inner filter class — implements {@link Ordered} so it runs right after
     * {@link CorrelationIdGatewayFilterFactory.CorrelationIdGatewayFilter} (order -999).
     */
    public static class RequestLoggerGatewayFilter implements GatewayFilter, Ordered {

        private static final int DEFAULT_MAX_BODY_BYTES = 4096;

        private final GatewayTelemetryPublisher telemetryPublisher;
        private final Config config;

        public RequestLoggerGatewayFilter(GatewayTelemetryPublisher telemetryPublisher, Config config) {
            this.telemetryPublisher = telemetryPublisher;
            this.config = config;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Instant requestedAt = Instant.now();
            long startNano = System.nanoTime();

            ServerHttpRequest req = exchange.getRequest();
            String correlationId = req.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);

            // ── Replay guard ─────────────────────────────────────────────────
            // Replayed requests carry X-Routify-Replay to prevent a second
            // request_log row from being created. Strip the header so it never
            // reaches the upstream, then pass through without logging.
            if (req.getHeaders().containsKey(REPLAY_HEADER)) {
                log.debug("[{}] Replay request — skipping telemetry logging", correlationId);
                ServerHttpRequest stripped = req.mutate().headers(h -> h.remove(REPLAY_HEADER)).build();
                return chain.filter(exchange.mutate().request(stripped).build());
            }

            log.debug("[{}] → {} {}", correlationId, req.getMethod(), req.getURI());

            int maxBodyBytes = config.getMaxBodyLogSize() > 0
                    ? config.getMaxBodyLogSize() : DEFAULT_MAX_BODY_BYTES;

            // ── Optionally capture request body ──────────────────────────────
            if (config.isLogRequestBody()) {
                AtomicReference<String> capturedReqBody = new AtomicReference<>();

                ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(req) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return DataBufferUtils.join(super.getBody())
                                .map(buf -> {
                                    byte[] bytes = new byte[Math.min(buf.readableByteCount(), maxBodyBytes)];
                                    buf.read(bytes);
                                    DataBufferUtils.release(buf);
                                    capturedReqBody.set(new String(bytes, StandardCharsets.UTF_8));
                                    return exchange.getResponse().bufferFactory().wrap(bytes);
                                })
                                .flux();
                    }
                };

                // ── Optionally capture response body ──────────────────────────
                if (config.isLogResponseBody()) {
                    AtomicReference<String> capturedRespBody = new AtomicReference<>();
                    ServerHttpResponse originalResponse = exchange.getResponse();

                    ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                        @Override
                        public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                            return DataBufferUtils.join(Flux.from(body))
                                    .flatMap(buf -> {
                                        byte[] bytes = new byte[Math.min(buf.readableByteCount(), maxBodyBytes)];
                                        buf.read(bytes);
                                        DataBufferUtils.release(buf);
                                        capturedRespBody.set(new String(bytes, StandardCharsets.UTF_8));
                                        DataBuffer newBuf = originalResponse.bufferFactory().wrap(bytes);
                                        return super.writeWith(Mono.just(newBuf));
                                    });
                        }
                    };

                    ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(decoratedRequest).response(decoratedResponse).build();

                    return chain.filter(mutatedExchange).doOnEach(signal -> {
                        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
                        if (SignalType.ON_COMPLETE.equals(signal.getType()) || SignalType.ON_ERROR.equals(signal.getType())) {
                            handleSignal(signal, mutatedExchange, correlationId, elapsedMs,
                                    requestedAt, capturedReqBody.get(), capturedRespBody.get());
                        }
                    });
                }

                // request body only, no response body
                ServerWebExchange mutatedExchange = exchange.mutate().request(decoratedRequest).build();
                return chain.filter(mutatedExchange).doOnEach(signal -> {
                    long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
                    if (SignalType.ON_COMPLETE.equals(signal.getType()) || SignalType.ON_ERROR.equals(signal.getType())) {
                        handleSignal(signal, mutatedExchange, correlationId, elapsedMs,
                                requestedAt, capturedReqBody.get(), null);
                    }
                });
            }

            // ── No body capture — original path ──────────────────────────────
            return chain.filter(exchange).doOnEach(signal -> {
                long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
                if (SignalType.ON_COMPLETE.equals(signal.getType()) || SignalType.ON_ERROR.equals(signal.getType())) {
                    handleSignal(signal, exchange, correlationId, elapsedMs, requestedAt, null, null);
                }
            });
        }

        private void handleSignal(reactor.core.publisher.Signal<?> signal,
                                  ServerWebExchange exchange,
                                  String correlationId,
                                  long elapsedMs,
                                  Instant requestedAt,
                                  String requestBody,
                                  String responseBody) {
            ServerHttpRequest req = exchange.getRequest();
            URI requestURI = req.getURI();
            int failThreshold = config.getFailedStatusThreshold();

            if (SignalType.ON_ERROR.equals(signal.getType())) {
                Throwable thrown = Optional.ofNullable(signal.getThrowable()).orElseGet(Throwable::new);
                String errorMsg;
                int statusCode;

                if (thrown instanceof ResponseStatusException ex) {
                    statusCode = ex.getStatusCode().value();
                    errorMsg   = ex.getReason() != null ? ex.getReason() : ex.getMessage();
                    log.warn("[{}] ← {} {} — status={} reason='{}' elapsed={}ms",
                            correlationId, req.getMethod(), requestURI,
                            ex.getStatusCode(), ex.getReason(), elapsedMs);
                } else if (thrown instanceof ServiceUnavailableException) {
                    statusCode = 503;
                    errorMsg   = "Circuit breaker open";
                    log.warn("[{}] ← {} {} — CIRCUIT_OPEN elapsed={}ms",
                            correlationId, req.getMethod(), requestURI, elapsedMs);
                } else {
                    statusCode = 500;
                    errorMsg   = thrown.getMessage();
                    log.error("[{}] ← {} {} — ERROR message='{}' elapsed={}ms",
                            correlationId, req.getMethod(), requestURI,
                            thrown.getMessage(), elapsedMs, thrown);
                }

                boolean failed = statusCode >= failThreshold;
                publishTelemetry(exchange, correlationId, statusCode, elapsedMs,
                        requestedAt, errorMsg, failed, requestBody, responseBody);

            } else if (SignalType.ON_COMPLETE.equals(signal.getType())) {
                URI gatewayURI  = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                HttpStatusCode  status    = exchange.getResponse().getStatusCode();
                int statusCode  = status != null ? status.value() : 0;
                String statusStr = Optional.ofNullable(status)
                        .map(HttpStatusCode::toString).orElse("unknown");

                log.info("[{}] ← {} {} → {} status={} elapsed={}ms",
                        correlationId, req.getMethod(), requestURI,
                        gatewayURI, statusStr, elapsedMs);

                boolean failed = statusCode >= failThreshold;
                publishTelemetry(exchange, correlationId, statusCode, elapsedMs,
                        requestedAt, null, failed, requestBody, responseBody);
            }
        }

        private void publishTelemetry(ServerWebExchange exchange,
                                      String correlationId,
                                      int responseStatus,
                                      long elapsedMs,
                                      Instant requestedAt,
                                      String errorMessage,
                                      boolean failed,
                                      String requestBody,
                                      String responseBody) {
            try {
                ServerHttpRequest req = exchange.getRequest();

                String tenantId  = req.getHeaders().getFirst(RoutifyHeaders.TENANT_ID);
                String userId    = req.getHeaders().getFirst(RoutifyHeaders.AUTH_USER_ID);

                // Read route id and name from the SCG matched-route exchange attribute.
                // The route id is stored as "{tenantId}::{routeId}" — strip the prefix to get the bare UUID.
                // Route metadata contains "routeName" set by RouteDefinitionBuilder.
                Route matchedRoute = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                String routeId   = null;
                String routeName = null;
                if (matchedRoute != null) {
                    String fullId = matchedRoute.getId();
                    int sep = fullId.indexOf("::");
                    routeId   = sep >= 0 ? fullId.substring(sep + 2) : fullId;
                    Object nameAttr = matchedRoute.getMetadata().get("routeName");
                    routeName = nameAttr != null ? nameAttr.toString() : null;
                }

                URI upstreamUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

                Long reqSize  = req.getHeaders().getContentLength() > 0
                        ? req.getHeaders().getContentLength() : null;
                Long respSize = exchange.getResponse().getHeaders().getContentLength() > 0
                        ? exchange.getResponse().getHeaders().getContentLength() : null;

                String clientIp = Optional.ofNullable(req.getHeaders().getFirst("X-Forwarded-For"))
                        .orElseGet(() -> Optional.ofNullable(req.getRemoteAddress())
                                .map(InetSocketAddress::getHostString).orElse(null));

                String queryString = req.getURI().getRawQuery();

                TelemetryEvent event = new TelemetryEvent(
                        correlationId,
                        tenantId,
                        routeId,
                        routeName,
                        req.getMethod().name(),
                        req.getPath().value(),
                        queryString,
                        upstreamUri != null ? upstreamUri.toString() : null,
                        clientIp,
                        userId,
                        responseStatus,
                        elapsedMs,
                        reqSize,
                        respSize,
                        errorMessage,
                        failed,
                        requestedAt,
                        null, // filterTrace populated by individual filters in future iterations
                        sanitizeHeaders(req.getHeaders()),
                        sanitizeHeaders(exchange.getResponse().getHeaders()),
                        requestBody,
                        responseBody
                );

                telemetryPublisher.publish(event);
            } catch (Exception e) {
                log.warn("Failed to build telemetry event for correlationId={}: {}", correlationId, e.getMessage());
            }
        }

        private Map<String, String> sanitizeHeaders(HttpHeaders headers) {
            return headers.entrySet().stream()
                    .filter(e -> REDACTED_HEADERS.stream()
                            .noneMatch(redacted -> redacted.equalsIgnoreCase(e.getKey())))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.join(", ", e.getValue()),
                            (a, b) -> a
                    ));
        }

        @Override
        public int getOrder() {
            return -999; // right after CorrelationIdGatewayFilter (-1000)
        }
    }

    public static class Config {
        private boolean logRequestHeaders  = true;
        private boolean logResponseHeaders = true;
        private boolean logRequestBody     = false;
        private boolean logResponseBody    = false;
        /** Max bytes to capture from request/response body. Default 4096. */
        private int     maxBodyLogSize     = 4096;
        /**
         * Minimum HTTP status code that marks a request as {@code failed=true} and eligible
         * for replay. Defaults to 500 (server errors only).
         * Set to 400 to also flag client errors (4xx) as failed.
         */
        private int     failedStatusThreshold = 500;

        public boolean isLogRequestHeaders()             { return logRequestHeaders; }
        public void setLogRequestHeaders(boolean v)      { this.logRequestHeaders = v; }
        public boolean isLogResponseHeaders()            { return logResponseHeaders; }
        public void setLogResponseHeaders(boolean v)     { this.logResponseHeaders = v; }
        public boolean isLogRequestBody()                { return logRequestBody; }
        public void setLogRequestBody(boolean v)         { this.logRequestBody = v; }
        public boolean isLogResponseBody()               { return logResponseBody; }
        public void setLogResponseBody(boolean v)        { this.logResponseBody = v; }
        public int getMaxBodyLogSize()                   { return maxBodyLogSize; }
        public void setMaxBodyLogSize(int v)             { this.maxBodyLogSize = v; }
        public int getFailedStatusThreshold()            { return failedStatusThreshold > 0 ? failedStatusThreshold : 500; }
        public void setFailedStatusThreshold(int v)      { this.failedStatusThreshold = v; }
    }
}

