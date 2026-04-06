package io.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Gateway filter factory that adds, sets, and removes request headers before
 * forwarding the request to the upstream service.
 *
 * <p>All three operations are applied in order: remove → set → add, so a
 * "replace" can be expressed as a single set entry.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code set}    — map of header name → value; overwrites any existing value</li>
 *   <li>{@code add}    — map of header name → value; appends without removing existing</li>
 *   <li>{@code remove} — map of header name → any value (value is ignored); header is deleted</li>
 * </ul>
 *
 * <p>Filter type: {@code REQUEST_HEADER_MODIFY}
 */
@Slf4j
@Component
public class RequestHeaderModifyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestHeaderModifyGatewayFilterFactory.Config> {

    public RequestHeaderModifyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest.Builder builder = exchange.getRequest().mutate();

            // 1. Remove headers
            if (config.getRemove() != null) {
                config.getRemove().keySet().forEach(header -> {
                    log.debug("RequestHeaderModify: removing header '{}'", header);
                    builder.headers(h -> h.remove(header));
                });
            }

            // 2. Set (overwrite) headers
            if (config.getSet() != null) {
                config.getSet().forEach((header, value) -> {
                    log.debug("RequestHeaderModify: setting header '{}' = '{}'", header, value);
                    builder.header(header, value);
                });
            }

            // 3. Add (append) headers
            if (config.getAdd() != null) {
                config.getAdd().forEach((header, value) -> {
                    log.debug("RequestHeaderModify: adding header '{}' = '{}'", header, value);
                    builder.headers(h -> h.add(header, value));
                });
            }

            return chain.filter(exchange.mutate().request(builder.build()).build());
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

