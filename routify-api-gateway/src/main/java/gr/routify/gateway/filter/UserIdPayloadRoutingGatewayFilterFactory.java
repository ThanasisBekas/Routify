package gr.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routing filter — reads the cached request body ({@code CacheRequestBody} with
 * {@code bodyClass=java.util.Map} must precede this filter) and rewrites the
 * downstream URI to {@code alternativeUri} when {@code userId} is in the configured
 * allowlist. Fail-fast: missing or wrong cached body returns 500 immediately.
 */
@Component
@Slf4j
public class UserIdPayloadRoutingGatewayFilterFactory
        extends AbstractGatewayFilterFactory<UserIdPayloadRoutingGatewayFilterFactory.Config>
        implements Ordered {

    public UserIdPayloadRoutingGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new OrderedGatewayFilter((exchange, chain) -> {
            if (!config.isEnabled()) {
                return chain.filter(exchange);
            }

            log.debug("Resolving downstream server URI based on request payload userId");

            Object rawBody = exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR);

            if (rawBody == null) {
                log.error("UserIdPayloadRouting misconfiguration: no cached body found on route '{}'. " +
                          "Add 'CacheRequestBody' with args.bodyClass=java.util.Map before this filter.",
                          exchange.getRequest().getPath());
                return reactor.core.publisher.Mono.error(new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Route misconfiguration: CacheRequestBody filter with bodyClass=java.util.Map " +
                        "must precede UserIdPayloadRouting"));
            }

            if (!(rawBody instanceof Map)) {
                log.error("UserIdPayloadRouting misconfiguration: cached body is '{}' (expected Map).",
                          rawBody.getClass().getSimpleName());
                return reactor.core.publisher.Mono.error(new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Route misconfiguration: bodyClass must be java.util.Map"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) rawBody;
            String userId = Optional.ofNullable(body.get(config.getUserIdField()))
                    .map(Object::toString)
                    .orElse(null);

            if (userId != null && config.getAllowlistUserIds().contains(userId)) {
                URI originalRouteUri = Optional
                        .ofNullable((URI) exchange.getAttribute(
                                ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR))
                        .orElseThrow(() -> new IllegalStateException(
                                "No original route URI found for alternative routing"));

                URI alternateRouteUri = URI.create(config.getAlternativeUri());
                boolean encoded = ServerWebExchangeUtils.containsEncodedParts(originalRouteUri);

                UriComponentsBuilder builder = UriComponentsBuilder.fromUri(originalRouteUri)
                        .scheme(alternateRouteUri.getScheme())
                        .host(alternateRouteUri.getHost());

                if (alternateRouteUri.getPort() != -1) {
                    builder.port(alternateRouteUri.getPort());
                }

                URI finalUri = builder.build(encoded).toUri();
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, finalUri);
                log.info("UserIdPayloadRouting: userId='{}' matched — routing to alternative URI '{}'",
                        userId, finalUri);
            }

            return chain.filter(exchange);
        }, getOrder());
    }

    @Override
    public int getOrder() {
        return 10001; // Run after RouteToRequestUrlFilter (10000)
    }

    @Data
    public static class Config {
        private boolean enabled = true;
        /** JSON field in the request body that contains the user ID. */
        private String userIdField = "userId";
        /** List of user IDs to route to the alternative URI. */
        private List<String> allowlistUserIds = List.of();
        /** Alternative upstream URI for matching user IDs. */
        private String alternativeUri;
    }
}

