package io.routify.gateway.filter.validation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.routify.common.domain.TenantPlan;
import io.routify.gateway.filter.GatewayTenantPlanCache;
import io.routify.gateway.filter.TenantContextGatewayFilterFactory;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gateway filter factory that enforces a per-route maximum request body size.
 * Rejects oversized payloads early with HTTP 413 Payload Too Large.
 *
 * <p>Unlike the deprecated {@code VALIDATE_SIZE} which used SCG's built-in
 * {@code RequestSize} filter, this custom implementation supports configurable
 * error responses, tenant-aware limits, and Micrometer metrics.
 *
 * <h3>Enforcement stages:</h3>
 * <ol>
 *   <li><b>Stage 1 (header-based fast path)</b> — checks {@code Content-Length}
 *       header and rejects immediately if over the limit. No body buffering needed.</li>
 *   <li><b>Stage 2 (streaming enforcement)</b> — wraps the body with a decorator
 *       that counts bytes as they arrive. Rejects mid-stream if cumulative bytes
 *       exceed the limit.</li>
 * </ol>
 *
 * <h3>Config params:</h3>
 * <table>
 *   <tr><th>Param</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>maxSize</td><td>String</td><td>5MB</td><td>Max body size with suffix (KB, MB, GB)</td></tr>
 *   <tr><td>checkContentLength</td><td>boolean</td><td>true</td><td>Reject based on Content-Length header</td></tr>
 *   <tr><td>checkActualSize</td><td>boolean</td><td>true</td><td>Count actual body bytes for chunked transfers</td></tr>
 *   <tr><td>tenantAware</td><td>boolean</td><td>false</td><td>Use per-tenant size limits from TenantPlan</td></tr>
 * </table>
 *
 * <p>Filter type: {@code REQUEST_SIZE_LIMIT}
 */
@Slf4j
@Component
public class RequestSizeLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestSizeLimitGatewayFilterFactory.Config> {

    /** Pattern to parse size strings like "5MB", "512KB", "1GB", "1048576". */
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^(\\d+)\\s*(B|KB|MB|GB)?$", Pattern.CASE_INSENSITIVE);

    private final MeterRegistry meterRegistry;
    private final GatewayTenantPlanCache tenantPlanCache;

    public RequestSizeLimitGatewayFilterFactory(MeterRegistry meterRegistry,
                                                GatewayTenantPlanCache tenantPlanCache) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
        this.tenantPlanCache = tenantPlanCache;
    }

    @Override
    public GatewayFilter apply(Config config) {
        long maxBytes = parseSize(config.getMaxSize());
        boolean checkContentLength = config.isCheckContentLength();
        boolean checkActualSize = config.isCheckActualSize();
        boolean tenantAware = config.isTenantAware();

        String maxSizeHuman = config.getMaxSize() != null ? config.getMaxSize() : "5MB";

        log.info("RequestSizeLimit filter configured: maxSize={} ({}B) checkContentLength={} checkActualSize={} tenantAware={}",
                maxSizeHuman, maxBytes, checkContentLength, checkActualSize, tenantAware);

        Counter rejectedCounter = Counter.builder("routify.filter.request_size.rejected")
                .description("Requests rejected for exceeding the maximum body size")
                .register(meterRegistry);

        DistributionSummary sizeSummary = DistributionSummary.builder("routify.filter.request_size.bytes")
                .description("Request body size in bytes")
                .baseUnit("bytes")
                .register(meterRegistry);

        return new RequestSizeLimitFilter(
                maxBytes, maxSizeHuman, checkContentLength, checkActualSize, tenantAware,
                tenantPlanCache, rejectedCounter, sizeSummary);
    }

    /**
     * Inner filter implementing {@link Ordered} — runs early to reject oversized
     * requests before body buffering/transformation filters.
     */
    static final class RequestSizeLimitFilter implements GatewayFilter, Ordered {

        /** Runs early — before body transformation filters but after auth. */
        private static final int FILTER_ORDER = -500;

        private final long maxBytes;
        private final String maxSizeHuman;
        private final boolean checkContentLength;
        private final boolean checkActualSize;
        private final boolean tenantAware;
        private final GatewayTenantPlanCache tenantPlanCache;
        private final Counter rejectedCounter;
        private final DistributionSummary sizeSummary;

        RequestSizeLimitFilter(long maxBytes,
                               String maxSizeHuman,
                               boolean checkContentLength,
                               boolean checkActualSize,
                               boolean tenantAware,
                               GatewayTenantPlanCache tenantPlanCache,
                               Counter rejectedCounter,
                               DistributionSummary sizeSummary) {
            this.maxBytes = maxBytes;
            this.maxSizeHuman = maxSizeHuman;
            this.checkContentLength = checkContentLength;
            this.checkActualSize = checkActualSize;
            this.tenantAware = tenantAware;
            this.tenantPlanCache = tenantPlanCache;
            this.rejectedCounter = rejectedCounter;
            this.sizeSummary = sizeSummary;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            long effectiveMaxBytes = resolveMaxBytes(exchange);
            String effectiveMaxSizeHuman = tenantAware ? formatBytes(effectiveMaxBytes) : maxSizeHuman;

            // ── Stage 1: Content-Length header check (fast path) ──────────────
            if (checkContentLength) {
                long contentLength = exchange.getRequest().getHeaders().getContentLength();
                if (contentLength > effectiveMaxBytes) {
                    log.debug("RequestSizeLimit: Content-Length {} exceeds maxSize {} — rejecting",
                            contentLength, effectiveMaxBytes);
                    sizeSummary.record(contentLength);
                    rejectedCounter.increment();
                    return rejectPayloadTooLarge(exchange, effectiveMaxBytes, effectiveMaxSizeHuman);
                }
                // Record known Content-Length for metrics
                if (contentLength > 0) {
                    sizeSummary.record(contentLength);
                }
            }

            // ── Stage 2: Streaming byte counter ──────────────────────────────
            if (checkActualSize) {
                AtomicLong byteCounter = new AtomicLong(0);

                ServerHttpRequestDecorator decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return super.getBody().map(dataBuffer -> {
                            long currentTotal = byteCounter.addAndGet(dataBuffer.readableByteCount());
                            if (currentTotal > effectiveMaxBytes) {
                                log.debug("RequestSizeLimit: streamed {}B exceeds maxSize {}B — rejecting",
                                        currentTotal, effectiveMaxBytes);
                                throw new RequestSizeExceededException(currentTotal, effectiveMaxBytes);
                            }
                            return dataBuffer;
                        });
                    }
                };

                return chain.filter(exchange.mutate().request(decorated).build())
                        .onErrorResume(RequestSizeExceededException.class, ex -> {
                            sizeSummary.record(ex.actualSize);
                            rejectedCounter.increment();
                            return rejectPayloadTooLarge(exchange, effectiveMaxBytes, effectiveMaxSizeHuman);
                        });
            }

            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return FILTER_ORDER;
        }

        /**
         * Resolves the effective max bytes. When tenant-aware mode is active,
         * reads the tenant ID from the exchange attribute (set by
         * {@link TenantContextGatewayFilterFactory}) and looks up the plan's
         * {@code maxRequestBodySize}. Falls back to the static {@code maxBytes}.
         */
        private long resolveMaxBytes(ServerWebExchange exchange) {
            if (!tenantAware) {
                return maxBytes;
            }

            String tenantIdStr = exchange.getAttribute(TenantContextGatewayFilterFactory.ATTR_TENANT_ID);
            if (tenantIdStr == null || tenantIdStr.isBlank()) {
                log.debug("RequestSizeLimit: tenantAware=true but no tenant ID on exchange — falling back to static maxSize");
                return maxBytes;
            }

            try {
                UUID tenantId = UUID.fromString(tenantIdStr);
                TenantPlan plan = tenantPlanCache.getPlan(tenantId);
                long planLimit = plan.maxRequestBodySize();
                log.debug("RequestSizeLimit: tenant={} plan={} maxRequestBodySize={}",
                        tenantIdStr, plan, planLimit);
                return planLimit;
            } catch (IllegalArgumentException e) {
                log.debug("RequestSizeLimit: invalid tenant UUID '{}' — falling back to static maxSize", tenantIdStr);
                return maxBytes;
            }
        }

        private Mono<Void> rejectPayloadTooLarge(ServerWebExchange exchange, long effectiveMaxBytes, String effectiveMaxSizeHuman) {
            return GatewayProblemResponse.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .errorCode("REQUEST_SIZE_EXCEEDED")
                    .detail("Request body exceeds the maximum allowed size of %s", effectiveMaxSizeHuman)
                    .extension("maxSize", effectiveMaxSizeHuman)
                    .extension("maxSizeBytes", effectiveMaxBytes)
                    .write(exchange);
        }
    }

    /**
     * Internal exception used to signal that the streaming byte counter
     * detected an oversized request body.
     */
    static final class RequestSizeExceededException extends RuntimeException {
        final long actualSize;
        final long maxSize;

        RequestSizeExceededException(long actualSize, long maxSize) {
            super("Request body size %d exceeds maximum %d".formatted(actualSize, maxSize));
            this.actualSize = actualSize;
            this.maxSize = maxSize;
        }
    }

    /**
     * Parses a human-readable size string (e.g. "5MB", "512KB", "1GB", "1048576")
     * into bytes. If the input is null, blank, or unparseable, defaults to 5 MB.
     *
     * @param sizeStr the size string to parse
     * @return the size in bytes
     */
    static long parseSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isBlank()) {
            return 5L * 1024 * 1024; // 5MB default
        }

        Matcher matcher = SIZE_PATTERN.matcher(sizeStr.strip());
        if (!matcher.matches()) {
            log.warn("RequestSizeLimit: unparseable maxSize '{}' — defaulting to 5MB", sizeStr);
            return 5L * 1024 * 1024;
        }

        long value = Long.parseLong(matcher.group(1));
        String suffix = matcher.group(2);
        if (suffix == null || suffix.isBlank()) {
            return value; // plain bytes
        }

        return switch (suffix.toUpperCase(Locale.ROOT)) {
            case "B"  -> value;
            case "KB" -> value * 1024;
            case "MB" -> value * 1024 * 1024;
            case "GB" -> value * 1024 * 1024 * 1024;
            default   -> value;
        };
    }

    /**
     * Formats a byte count as a human-readable string (e.g. "5MB", "512KB").
     */
    static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024 && bytes % (1024L * 1024 * 1024) == 0) {
            return (bytes / (1024L * 1024 * 1024)) + "GB";
        }
        if (bytes >= 1024L * 1024 && bytes % (1024L * 1024) == 0) {
            return (bytes / (1024L * 1024)) + "MB";
        }
        if (bytes >= 1024L && bytes % 1024L == 0) {
            return (bytes / 1024L) + "KB";
        }
        return bytes + "B";
    }

    @Data
    public static class Config {
        /**
         * Maximum request body size. Supports suffixes: B, KB, MB, GB.
         * Examples: "5MB", "512KB", "1GB", "1048576".
         * Default: "5MB".
         */
        private String maxSize = "5MB";
        /** Check Content-Length header for fast rejection. Default: true. */
        private boolean checkContentLength = true;
        /** Count actual body bytes for chunked transfers. Default: true. */
        private boolean checkActualSize = true;
        /** When true, resolve per-tenant size limits from TenantPlan. Default: false. */
        private boolean tenantAware = false;
    }
}

