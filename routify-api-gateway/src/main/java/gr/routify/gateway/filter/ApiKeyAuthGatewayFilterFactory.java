package gr.routify.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gateway filter for API Key authentication.
 *
 * <p>Validates the API key from a configurable header or query parameter.
 * In production, keys are validated against Redis or an identity service.
 *
 * <p>Config params:
 * <ul>
 *   <li>{@code headerName} — header to read the API key from (default: {@code X-API-Key})</li>
 *   <li>{@code queryParam} — query param fallback (optional)</li>
 *   <li>{@code validationMode} — HEADER_HASH (local Redis) or REMOTE (identity service)</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiKeyAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ApiKeyAuthGatewayFilterFactory.Config> {

    public ApiKeyAuthGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String apiKey = extractApiKey(exchange, config);

            if (apiKey == null || apiKey.isBlank()) {
                return forbidden(exchange, "MISSING_API_KEY", "API Key is required");
            }

            // TODO: Validate against Redis/identity service in production
            // For now, inject the API key as a request header for downstream
            var mutatedRequest = exchange.getRequest().mutate()
                    .header("X-Auth-Api-Key", apiKey)
                    .header("X-Auth-Type", "API_KEY")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    private String extractApiKey(ServerWebExchange exchange, Config config) {
        String headerName = config.getHeaderName() != null ? config.getHeaderName() : "X-API-Key";
        String apiKey = exchange.getRequest().getHeaders().getFirst(headerName);

        if (apiKey == null && config.getQueryParam() != null) {
            apiKey = exchange.getRequest().getQueryParams().getFirst(config.getQueryParam());
        }
        return apiKey;
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String errorCode, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set("Content-Type", "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Unauthorized","status":401,
                 "errorCode":"%s","detail":"%s"}
                """.formatted(errorCode, detail).strip();
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes())));
    }

    public static class Config {
        private String headerName = "X-API-Key";
        private String queryParam;
        private String validationMode = "REDIS";

        public String getHeaderName()             { return headerName; }
        public void setHeaderName(String h)       { this.headerName = h; }
        public String getQueryParam()             { return queryParam; }
        public void setQueryParam(String q)       { this.queryParam = q; }
        public String getValidationMode()         { return validationMode; }
        public void setValidationMode(String m)   { this.validationMode = m; }
    }
}

