package gr.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Gateway filter factory that sets and removes query parameters on the
 * upstream request URI before routing.
 *
 * <p>Operations are applied in order: remove → set.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code set}    — map of param name → value; replaces any existing value with that name</li>
 *   <li>{@code remove} — map of param name → any value (value is ignored); param is deleted</li>
 * </ul>
 *
 * <p>Filter type: {@code QUERY_PARAM_MODIFY}
 */
@Slf4j
@Component
public class QueryParamModifyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<QueryParamModifyGatewayFilterFactory.Config> {

    public QueryParamModifyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest req = exchange.getRequest();
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUri(req.getURI());

            // 1. Remove params
            if (config.getRemove() != null) {
                config.getRemove().keySet().forEach(param -> {
                    log.debug("QueryParamModify: removing param '{}'", param);
                    uriBuilder.replaceQueryParam(param);
                });
            }

            // 2. Set (replace) params
            if (config.getSet() != null) {
                config.getSet().forEach((param, value) -> {
                    log.debug("QueryParamModify: setting param '{}' = '{}'", param, value);
                    uriBuilder.replaceQueryParam(param, value);
                });
            }

            URI newUri = uriBuilder.build(true).toUri();
            ServerHttpRequest mutated = req.mutate().uri(newUri).build();
            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    @Data
    public static class Config {
        /** Query params to set/replace. Key = param name, value = new value. */
        private Map<String, String> set;
        /** Query params to remove. Key = param name, value is ignored. */
        private Map<String, String> remove;
    }
}

