package io.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gateway filter factory that adds, sets, and removes response headers after
 * the upstream service has responded, before the response is written to the client.
 *
 * <p>Operations are applied in order: remove → set → add.
 *
 * <p>Header modifications are registered via {@link ServerHttpResponse#beforeCommit}
 * so they are applied just before the response is committed to the wire — this
 * guarantees headers are written even for streamed/chunked responses where the
 * body may start being sent before the filter chain completes.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code set}    — map of header name → value; overwrites any existing value</li>
 *   <li>{@code add}    — map of header name → value; appends without removing existing</li>
 *   <li>{@code remove} — map of header name → any value (value is ignored); header is deleted</li>
 * </ul>
 *
 * <p>Filter type: {@code RESPONSE_HEADER_MODIFY}
 */
@Slf4j
@Component
public class ResponseHeaderModifyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ResponseHeaderModifyGatewayFilterFactory.Config> {

    public ResponseHeaderModifyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            exchange.getResponse().beforeCommit(() -> {
                ServerHttpResponse response = exchange.getResponse();

                // 1. Remove headers
                if (config.getRemove() != null) {
                    config.getRemove().keySet().forEach(header -> {
                        log.debug("ResponseHeaderModify: removing header '{}'", header);
                        response.getHeaders().remove(header);
                    });
                }

                // 2. Set (overwrite) headers
                if (config.getSet() != null) {
                    config.getSet().forEach((header, value) -> {
                        log.debug("ResponseHeaderModify: setting header '{}' = '{}'", header, value);
                        response.getHeaders().set(header, value);
                    });
                }

                // 3. Add (append) headers
                if (config.getAdd() != null) {
                    config.getAdd().forEach((header, value) -> {
                        log.debug("ResponseHeaderModify: adding header '{}' = '{}'", header, value);
                        response.getHeaders().add(header, value);
                    });
                }

                return Mono.empty();
            });

            return chain.filter(exchange);
        };
    }

    @Data
    public static class Config {
        /** Headers to overwrite. Key = header name, value = new value. */
        private Map<String, String> set;
        /** Headers to append. Key = header name, value = value to add. */
        private Map<String, String> add;
        /** Headers to remove. Key = header name, value is ignored. */
        private Map<String, String> remove;
    }
}

