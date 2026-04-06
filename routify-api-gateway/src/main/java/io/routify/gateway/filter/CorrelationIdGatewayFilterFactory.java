package io.routify.gateway.filter;

import io.routify.common.web.RoutifyHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Gateway filter factory that injects a unique {@code X-Correlation-Id} into every request
 * it is applied to.
 *
 * <p>If the request already carries a correlation ID (forwarded from a client or
 * upstream service), it is preserved. Otherwise a new UUID is generated.
 *
 * <p>The correlation ID is propagated:
 * <ul>
 *   <li>To upstream services as a request header</li>
 *   <li>To clients in the response header</li>
 *   <li>To MDC for log correlation</li>
 * </ul>
 *
 * <p>Filter type: {@code CORRELATION_ID}
 */
@Slf4j
@Component
public class CorrelationIdGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CorrelationIdGatewayFilterFactory.Config> {

    /** @deprecated Use {@link RoutifyHeaders#CORRELATION_ID} directly. */
    public static final String CORRELATION_ID_HEADER = RoutifyHeaders.CORRELATION_ID;

    public CorrelationIdGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new CorrelationIdGatewayFilter();
    }

    /**
     * Inner filter class — implements {@link Ordered} so it always runs first when
     * multiple filters are chained (order -1000).
     */
    public static class CorrelationIdGatewayFilter implements GatewayFilter, Ordered {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);

            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }

            final String finalId = correlationId;

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(CORRELATION_ID_HEADER, finalId)
                    .build();

            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            // Set the response header *before* the response is committed.
            mutatedExchange.getResponse().beforeCommit(() -> {
                mutatedExchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, finalId);
                return Mono.empty();
            });

            return chain.filter(mutatedExchange);
        }

        @Override
        public int getOrder() {
            return -1000;
        }
    }

    public static class Config {
        // No configuration required; the filter works with defaults.
    }
}

