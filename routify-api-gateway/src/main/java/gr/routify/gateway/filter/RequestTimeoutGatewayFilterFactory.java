package gr.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Gateway filter factory that enforces a per-route request timeout.
 *
 * <p>If the upstream does not respond within {@code timeoutMs} milliseconds the
 * request is cancelled and the client receives an RFC 9457 {@code 504 Gateway Timeout}
 * Problem Detail JSON response.
 *
 * <p>This complements the global {@code spring.cloud.gateway.httpclient.response-timeout}
 * property by allowing individual routes to override the global value — including setting
 * a tighter deadline for latency-sensitive endpoints or a longer one for batch jobs.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code timeoutMs} — timeout in milliseconds (default: 30000 = 30 s)</li>
 * </ul>
 *
 * <p>Filter type: {@code TIMEOUT}
 */
@Slf4j
@Component
public class RequestTimeoutGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestTimeoutGatewayFilterFactory.Config> {

    public RequestTimeoutGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        long timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 30_000L;
        Duration timeout = Duration.ofMillis(timeoutMs);

        return (exchange, chain) -> chain.filter(exchange)
                .timeout(timeout)
                .onErrorResume(TimeoutException.class, ex -> {
                    log.warn("RequestTimeout: upstream did not respond within {}ms for {} {}",
                            timeoutMs,
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getURI());
                    return gatewayTimeout(exchange, timeoutMs);
                });
    }

    private Mono<Void> gatewayTimeout(org.springframework.web.server.ServerWebExchange exchange, long timeoutMs) {
        ServerHttpResponse resp = exchange.getResponse();
        if (resp.isCommitted()) {
            return Mono.empty();
        }
        resp.setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
        resp.getHeaders().set("Content-Type", "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Gateway Timeout","status":504,\
                "detail":"The upstream service did not respond within %dms."}""".formatted(timeoutMs);
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body.getBytes())));
    }

    @Data
    public static class Config {
        /** Timeout in milliseconds. Default: 30000 (30 seconds). */
        private long timeoutMs = 30_000L;
    }
}

