package io.routify.gateway.filter;

import io.routify.common.event.RequestTelemetryEvent;
import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.telemetry.GatewayTelemetryPublisher;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
 * <h3>Configurable features:</h3>
 * <ul>
 *   <li>{@code maxBodyCaptureBytes} — caps body capture with a hard 64 KB limit</li>
 *   <li>{@code samplingRate} — probabilistic sampling to control telemetry volume</li>
 *   <li>{@code headerAllowlist}/{@code headerDenylist} — fine-grained header capture control</li>
 *   <li>{@code skipPaths} — regex patterns for paths to exclude from logging</li>
 * </ul>
 *
 * <p>Filter type: {@code REQUEST_LOGGER}
 */
@Component
@Slf4j
public class RequestLoggerGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestLoggerGatewayFilterFactory.Config> {

    /** Default set of headers to redact from telemetry (security-sensitive). */
    private static final Set<String> DEFAULT_REDACTED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie",
            RoutifyHeaders.API_KEY.toLowerCase(),
            RoutifyHeaders.AUTH_TOKEN.toLowerCase(),
            RoutifyHeaders.REPLAY_MARKER.toLowerCase()
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
        // Pre-compile skip-path patterns at config bind time (not per-request)
        List<Pattern> skipPatterns = compileSkipPatterns(config.getSkipPaths());

        // Pre-compute lowercase deny set for fast lookup
        Set<String> denySet = config.getHeaderDenylist() != null && !config.getHeaderDenylist().isEmpty()
                ? config.getHeaderDenylist().stream().map(String::toLowerCase).collect(Collectors.toSet())
                : DEFAULT_REDACTED_HEADERS;

        // Pre-compute lowercase allow set (empty = capture all)
        Set<String> allowSet = config.getHeaderAllowlist() != null && !config.getHeaderAllowlist().isEmpty()
                ? config.getHeaderAllowlist().stream().map(String::toLowerCase).collect(Collectors.toSet())
                : Set.of();

        // Clamp maxBodyCaptureBytes: default 4096, hard upper bound 65536 (64 KB)
        int maxBodyBytes = resolveMaxBodyCaptureBytes(config);

        // Clamp samplingRate to [0.0, 1.0]
        double samplingRate = Math.max(0.0, Math.min(1.0, config.getSamplingRate()));

        return new RequestLoggerGatewayFilter(
                telemetryPublisher, config, skipPatterns, denySet, allowSet,
                maxBodyBytes, samplingRate);
    }

    private static List<Pattern> compileSkipPatterns(List<String> skipPaths) {
        if (skipPaths == null || skipPaths.isEmpty()) return List.of();
        List<Pattern> patterns = new ArrayList<>(skipPaths.size());
        for (String glob : skipPaths) {
            // Convert simple glob patterns to regex: ** → .*, * → [^/]*
            String regex = glob
                    .replace(".", "\\.")
                    .replace("/**", "/.*")
                    .replace("/*", "/[^/]*")
                    .replace("*", "[^/]*");
            patterns.add(Pattern.compile("^" + regex + "$"));
        }
        return List.copyOf(patterns);
    }

    private static final int DEFAULT_MAX_BODY_BYTES = 4096;
    private static final int HARD_UPPER_BOUND = 65_536; // 64 KB

    private static int resolveMaxBodyCaptureBytes(Config config) {
        int configured = config.getMaxBodyCaptureBytes() > 0
                ? config.getMaxBodyCaptureBytes()
                : (config.getMaxBodyLogSize() > 0 ? config.getMaxBodyLogSize() : DEFAULT_MAX_BODY_BYTES);
        return Math.min(configured, HARD_UPPER_BOUND);
    }

    /**
     * Inner filter class — implements {@link Ordered} so it runs right after
     * {@link CorrelationIdGatewayFilterFactory.CorrelationIdGatewayFilter} (order -999).
     */
    public static class RequestLoggerGatewayFilter implements GatewayFilter, Ordered {

        private final GatewayTelemetryPublisher telemetryPublisher;
        private final Config config;
        private final List<Pattern> skipPatterns;
        private final Set<String> denySet;
        private final Set<String> allowSet;
        private final int maxBodyBytes;
        private final double samplingRate;

        public RequestLoggerGatewayFilter(GatewayTelemetryPublisher telemetryPublisher,
                                          Config config,
                                          List<Pattern> skipPatterns,
                                          Set<String> denySet,
                                          Set<String> allowSet,
                                          int maxBodyBytes,
                                          double samplingRate) {
            this.telemetryPublisher = telemetryPublisher;
            this.config = config;
            this.skipPatterns = skipPatterns;
            this.denySet = denySet;
            this.allowSet = allowSet;
            this.maxBodyBytes = maxBodyBytes;
            this.samplingRate = samplingRate;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Instant requestedAt = Instant.now();
            long startNano = System.nanoTime();

            ServerHttpRequest req = exchange.getRequest();
            String correlationId = req.getHeaders().getFirst(RoutifyHeaders.CORRELATION_ID);

            // ── Replay guard ─────────────────────────────────────────────────
            if (req.getHeaders().containsHeader(REPLAY_HEADER)) {
                log.debug("[{}] Replay request — skipping telemetry logging", correlationId);
                ServerHttpRequest stripped = req.mutate().headers(h -> h.remove(REPLAY_HEADER)).build();
                return chain.filter(exchange.mutate().request(stripped).build());
            }

            // ── Skip-path check ──────────────────────────────────────────────
            String requestPath = req.getPath().value();
            if (matchesSkipPattern(requestPath)) {
                log.trace("[{}] Path '{}' matches skipPaths — bypassing logger", correlationId, requestPath);
                return chain.filter(exchange);
            }

            // ── Sampling decision ────────────────────────────────────────────
            boolean sampled = samplingRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < samplingRate;
            if (!sampled) {
                log.trace("[{}] Request not sampled (rate={})", correlationId, samplingRate);
            }

            log.debug("[{}] → {} {}", correlationId, req.getMethod(), req.getURI());

            // ── Optionally capture request body ──────────────────────────────
            if (config.isLogRequestBody()) {
                AtomicReference<String> capturedReqBody = new AtomicReference<>();

                return DataBufferUtils.join(req.getBody())
                        .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                        .flatMap(buf -> {
                            int readableBytes = buf.readableByteCount();
                            int captureSize = Math.min(readableBytes, maxBodyBytes);
                            byte[] bytes = new byte[captureSize];
                            buf.read(bytes);

                            // Read remaining bytes so they can be replayed to upstream
                            byte[] fullBytes;
                            if (readableBytes > captureSize) {
                                fullBytes = new byte[readableBytes];
                                // Re-read from start — need to rebuild
                                buf.readPosition(0);
                                buf.read(fullBytes);
                            } else {
                                fullBytes = bytes;
                            }
                            DataBufferUtils.release(buf);

                            if (captureSize > 0) {
                                String captured = new String(bytes, StandardCharsets.UTF_8);
                                if (readableBytes > maxBodyBytes) {
                                    captured += " [TRUNCATED at " + maxBodyBytes + " bytes]";
                                }
                                capturedReqBody.set(captured);
                            }

                            // Re-wrap the full bytes so downstream filters/upstream still get the body
                            ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(
                                    req.mutate().build()) {
                                @Override
                                public Flux<DataBuffer> getBody() {
                                    return Flux.just(exchange.getResponse().bufferFactory().wrap(fullBytes));
                                }
                            };

                            if (config.isLogResponseBody()) {
                                AtomicReference<String> capturedRespBody = new AtomicReference<>();
                                ServerHttpResponse originalResponse = exchange.getResponse();

                                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                                    @Override
                                    public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                                        return DataBufferUtils.join(Flux.from(body))
                                                .flatMap(respBuf -> {
                                                    int respReadable = respBuf.readableByteCount();
                                                    int respCapture = Math.min(respReadable, maxBodyBytes);
                                                    byte[] respBytes = new byte[respCapture];
                                                    respBuf.read(respBytes);

                                                    // Read full response for downstream
                                                    byte[] fullRespBytes;
                                                    if (respReadable > respCapture) {
                                                        fullRespBytes = new byte[respReadable];
                                                        respBuf.readPosition(0);
                                                        respBuf.read(fullRespBytes);
                                                    } else {
                                                        fullRespBytes = respBytes;
                                                    }
                                                    DataBufferUtils.release(respBuf);

                                                    String capturedResp = new String(respBytes, StandardCharsets.UTF_8);
                                                    if (respReadable > maxBodyBytes) {
                                                        capturedResp += " [TRUNCATED at " + maxBodyBytes + " bytes]";
                                                    }
                                                    capturedRespBody.set(capturedResp);

                                                    DataBuffer newBuf = originalResponse.bufferFactory().wrap(fullRespBytes);
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
                                                requestedAt, capturedReqBody.get(), capturedRespBody.get(), sampled);
                                    }
                                });
                            }

                            // request body only, no response body
                            ServerWebExchange mutatedExchange = exchange.mutate().request(decoratedRequest).build();
                            return chain.filter(mutatedExchange).doOnEach(signal -> {
                                long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
                                if (SignalType.ON_COMPLETE.equals(signal.getType()) || SignalType.ON_ERROR.equals(signal.getType())) {
                                    handleSignal(signal, mutatedExchange, correlationId, elapsedMs,
                                            requestedAt, capturedReqBody.get(), null, sampled);
                                }
                            });
                        });
            }

            // ── No body capture — original path ──────────────────────────────
            return chain.filter(exchange).doOnEach(signal -> {
                long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
                if (SignalType.ON_COMPLETE.equals(signal.getType()) || SignalType.ON_ERROR.equals(signal.getType())) {
                    handleSignal(signal, exchange, correlationId, elapsedMs, requestedAt, null, null, sampled);
                }
            });
        }

        private boolean matchesSkipPattern(String path) {
            for (Pattern pattern : skipPatterns) {
                if (pattern.matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        }

        private void handleSignal(reactor.core.publisher.Signal<?> signal,
                                  ServerWebExchange exchange,
                                  String correlationId,
                                  long elapsedMs,
                                  Instant requestedAt,
                                  String requestBody,
                                  String responseBody,
                                  boolean sampled) {
            ServerHttpRequest req = exchange.getRequest();
            int failThreshold = config.getFailedStatusThreshold();

            // Resolve route info for MDC
            Route matchedRoute = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = null;
            if (matchedRoute != null) {
                String fullId = matchedRoute.getId();
                int sep = fullId.indexOf("::");
                routeId = sep >= 0 ? fullId.substring(sep + 2) : fullId;
            }

            String clientIp = Optional.ofNullable(req.getHeaders().getFirst("X-Forwarded-For"))
                    .orElseGet(() -> Optional.ofNullable(req.getRemoteAddress())
                            .map(InetSocketAddress::getHostString).orElse(null));

            if (SignalType.ON_ERROR.equals(signal.getType())) {
                Throwable thrown = Optional.ofNullable(signal.getThrowable()).orElseGet(Throwable::new);
                String errorMsg;
                int statusCode;

                if (thrown instanceof ResponseStatusException ex) {
                    statusCode = ex.getStatusCode().value();
                    errorMsg   = ex.getReason() != null ? ex.getReason() : ex.getMessage();
                } else if (thrown instanceof ServiceUnavailableException) {
                    statusCode = 503;
                    errorMsg   = "Circuit breaker open";
                } else {
                    statusCode = 500;
                    errorMsg   = thrown.getMessage();
                }

                // ── Structured MDC logging ────────────────────────────────────
                enrichMdc(req, statusCode, elapsedMs, correlationId, routeId, clientIp);
                if (statusCode == 503) {
                    log.warn("Request completed — CIRCUIT_OPEN");
                } else if (statusCode >= 500) {
                    log.error("Request completed — error: {}", errorMsg, thrown);
                } else {
                    log.warn("Request completed — status={} reason='{}'", statusCode, errorMsg);
                }
                clearMdc();

                boolean failed = statusCode >= failThreshold;
                if (sampled) {
                    publishTelemetry(exchange, correlationId, statusCode, elapsedMs,
                            requestedAt, errorMsg, failed, requestBody, responseBody);
                }

            } else if (SignalType.ON_COMPLETE.equals(signal.getType())) {
                HttpStatusCode  status    = exchange.getResponse().getStatusCode();
                int statusCode  = status != null ? status.value() : 0;

                // ── Structured MDC logging ────────────────────────────────────
                enrichMdc(req, statusCode, elapsedMs, correlationId, routeId, clientIp);
                log.info("Request completed");
                clearMdc();

                boolean failed = statusCode >= failThreshold;
                if (sampled) {
                    publishTelemetry(exchange, correlationId, statusCode, elapsedMs,
                            requestedAt, null, failed, requestBody, responseBody);
                }
            }
        }

        // ─── Structured MDC helpers ──────────────────────────────────────────

        private void enrichMdc(ServerHttpRequest req, int status, long elapsedMs,
                               String correlationId, String routeId, String clientIp) {
            org.slf4j.MDC.put("method", req.getMethod().name());
            org.slf4j.MDC.put("path", req.getPath().value());
            org.slf4j.MDC.put("status", String.valueOf(status));
            org.slf4j.MDC.put("elapsedMs", String.valueOf(elapsedMs));
            if (correlationId != null) org.slf4j.MDC.put("correlationId", correlationId);
            if (routeId != null) org.slf4j.MDC.put("routeId", routeId);
            if (clientIp != null) org.slf4j.MDC.put("clientIp", clientIp);
        }

        private void clearMdc() {
            org.slf4j.MDC.remove("method");
            org.slf4j.MDC.remove("path");
            org.slf4j.MDC.remove("status");
            org.slf4j.MDC.remove("elapsedMs");
            org.slf4j.MDC.remove("correlationId");
            org.slf4j.MDC.remove("routeId");
            org.slf4j.MDC.remove("clientIp");
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

                RequestTelemetryEvent event = new RequestTelemetryEvent(
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

        /**
         * Sanitize headers according to allowlist/denylist config.
         * Deny always overrides allow. Denied headers appear as {@code [REDACTED]}.
         */
        private Map<String, String> sanitizeHeaders(HttpHeaders headers) {
            return headers.headerNames().stream()
                    .filter(name -> {
                        // If allowlist is specified, only include headers in it
                        if (!allowSet.isEmpty() && !allowSet.contains(name.toLowerCase())) {
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toMap(
                            name -> name,
                            name -> denySet.contains(name.toLowerCase())
                                    ? "[REDACTED]"
                                    : String.join(", ", headers.getOrEmpty(name)),
                            (a, b) -> a
                    ));
        }

        @Override
        public int getOrder() {
            return -999; // right after CorrelationIdGatewayFilter (-1000)
        }
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    public static class Config {
        private boolean logRequestHeaders  = true;
        private boolean logResponseHeaders = true;
        private boolean logRequestBody     = false;
        private boolean logResponseBody    = false;

        /** @deprecated Use {@code maxBodyCaptureBytes} instead. Kept for backward compatibility. */
        @Deprecated
        private int     maxBodyLogSize     = 4096;

        /**
         * Max bytes to capture from request/response body. Default 4096.
         * Hard upper bound: 65536 (64 KB). Values above this are silently capped.
         */
        private int     maxBodyCaptureBytes = 0; // 0 = use maxBodyLogSize fallback

        /**
         * Minimum HTTP status code that marks a request as {@code failed=true} and eligible
         * for replay. Defaults to 500 (server errors only).
         */
        private int     failedStatusThreshold = 500;

        /**
         * Probabilistic sampling rate for telemetry event publishing.
         * Range: 0.0 (no events) to 1.0 (all events, default).
         * When < 1.0, only a random fraction of requests generate telemetry events.
         */
        private double  samplingRate = 1.0;

        /**
         * Header allowlist — when non-empty, only these headers are captured in telemetry.
         * Empty list (default) means capture all headers (except those in denylist).
         */
        private List<String> headerAllowlist = List.of();

        /**
         * Header denylist — headers to redact from telemetry.
         * Deny always overrides allow. Denied headers appear as {@code [REDACTED]}.
         * Default: inherits the built-in sensitive header set (Authorization, Cookie, etc.)
         */
        private List<String> headerDenylist = List.of();

        /**
         * Glob patterns for paths to exclude from logging and telemetry.
         * Matched requests bypass both logging and telemetry entirely.
         * Example: {@code ["/actuator/**", "/health", "/favicon.ico"]}
         */
        private List<String> skipPaths = List.of();

        // ─── Getters / Setters ────────────────────────────────────────────

        public boolean isLogRequestHeaders()             { return logRequestHeaders; }
        public void setLogRequestHeaders(boolean v)      { this.logRequestHeaders = v; }
        public boolean isLogResponseHeaders()            { return logResponseHeaders; }
        public void setLogResponseHeaders(boolean v)     { this.logResponseHeaders = v; }
        public boolean isLogRequestBody()                { return logRequestBody; }
        public void setLogRequestBody(boolean v)         { this.logRequestBody = v; }
        public boolean isLogResponseBody()               { return logResponseBody; }
        public void setLogResponseBody(boolean v)        { this.logResponseBody = v; }

        /** @deprecated Use {@link #getMaxBodyCaptureBytes()} */
        @Deprecated
        public int getMaxBodyLogSize()                   { return maxBodyLogSize; }
        /** @deprecated Use {@link #setMaxBodyCaptureBytes(int)} */
        @Deprecated
        public void setMaxBodyLogSize(int v)             { this.maxBodyLogSize = v; }

        public int getMaxBodyCaptureBytes()              { return maxBodyCaptureBytes; }
        public void setMaxBodyCaptureBytes(int v)        { this.maxBodyCaptureBytes = v; }

        public int getFailedStatusThreshold()            { return failedStatusThreshold > 0 ? failedStatusThreshold : 500; }
        public void setFailedStatusThreshold(int v)      { this.failedStatusThreshold = v; }

        public double getSamplingRate()                  { return samplingRate; }
        public void setSamplingRate(double v)            { this.samplingRate = v; }

        public List<String> getHeaderAllowlist()         { return headerAllowlist; }
        public void setHeaderAllowlist(List<String> v)   { this.headerAllowlist = v != null ? v : List.of(); }

        public List<String> getHeaderDenylist()          { return headerDenylist; }
        public void setHeaderDenylist(List<String> v)    { this.headerDenylist = v != null ? v : List.of(); }

        public List<String> getSkipPaths()               { return skipPaths; }
        public void setSkipPaths(List<String> v)         { this.skipPaths = v != null ? v : List.of(); }
    }
}

