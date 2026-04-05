package io.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Gateway filter factory that conditionally rewrites the upstream URI based on a
 * request-attribute predicate, enabling A/B routing, canary deployments, and
 * feature-flag-driven routing from within a single route definition.
 *
 * <h3>Evaluation order</h3>
 * <ol>
 *   <li><b>Header match</b> — if {@code conditionHeader} is set and the request carries
 *       that header with a value that matches {@code conditionPattern} (full regex match),
 *       the upstream is rewritten to {@code alternativeUri}.</li>
 *   <li><b>Query param match</b> — if {@code conditionParam} is set and the request carries
 *       that query param with a value matching {@code conditionPattern}, the upstream is
 *       rewritten to {@code alternativeUri}.</li>
 *   <li><b>No match</b> — the route proceeds to its configured upstream unchanged.</li>
 * </ol>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code conditionHeader}  — request header name to inspect (optional)</li>
 *   <li>{@code conditionParam}   — query param name to inspect (optional)</li>
 *   <li>{@code conditionPattern} — Java regex the header/param value must fully match
 *       (default: {@code .*} — matches any non-null value)</li>
 *   <li>{@code alternativeUri}   — upstream URI to route to when condition matches (required)</li>
 * </ul>
 *
 * <p>Filter type: {@code CONDITIONAL_ROUTE}
 */
@Slf4j
@Component
public class ConditionalRouteGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ConditionalRouteGatewayFilterFactory.Config> {

    public ConditionalRouteGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getAlternativeUri() == null || config.getAlternativeUri().isBlank()) {
            log.error("ConditionalRoute: 'alternativeUri' config is required but was not provided — filter disabled");
            return (exchange, chain) -> chain.filter(exchange);
        }

        String patternStr = config.getConditionPattern() != null
                ? config.getConditionPattern() : ".*";
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (Exception e) {
            log.error("ConditionalRoute: invalid conditionPattern '{}': {}", patternStr, e.getMessage());
            return (exchange, chain) -> chain.filter(exchange);
        }

        URI alternativeUri = URI.create(config.getAlternativeUri());

        return (exchange, chain) -> {
            ServerHttpRequest req = exchange.getRequest();
            String matchedValue = null;

            // 1. Check header
            if (config.getConditionHeader() != null && !config.getConditionHeader().isBlank()) {
                String headerVal = req.getHeaders().getFirst(config.getConditionHeader());
                if (headerVal != null && pattern.matcher(headerVal).matches()) {
                    matchedValue = headerVal;
                    log.debug("ConditionalRoute: header '{}' = '{}' matched pattern '{}'",
                            config.getConditionHeader(), headerVal, patternStr);
                }
            }

            // 2. Check query param (if header didn't match)
            if (matchedValue == null && config.getConditionParam() != null
                    && !config.getConditionParam().isBlank()) {
                String paramVal = req.getQueryParams().getFirst(config.getConditionParam());
                if (paramVal != null && pattern.matcher(paramVal).matches()) {
                    matchedValue = paramVal;
                    log.debug("ConditionalRoute: param '{}' = '{}' matched pattern '{}'",
                            config.getConditionParam(), paramVal, patternStr);
                }
            }

            if (matchedValue != null) {
                // Rewrite the gateway request URL attribute to the alternative URI
                URI originalUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                if (originalUri == null) {
                    // Route hasn't been resolved yet — mutate the request URI directly
                    URI newUri = UriComponentsBuilder.fromUri(req.getURI())
                            .scheme(alternativeUri.getScheme())
                            .host(alternativeUri.getHost())
                            .port(alternativeUri.getPort())
                            .build(true).toUri();
                    log.info("ConditionalRoute: rewriting upstream URI to '{}'", newUri);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);
                } else {
                    boolean encoded = ServerWebExchangeUtils.containsEncodedParts(originalUri);
                    URI newUri = UriComponentsBuilder.fromUri(originalUri)
                            .scheme(alternativeUri.getScheme())
                            .host(alternativeUri.getHost())
                            .port(alternativeUri.getPort())   // -1 means "no explicit port" which UriComponentsBuilder handles correctly
                            .build(encoded).toUri();
                    log.info("ConditionalRoute: rewriting upstream URI '{}' → '{}'", originalUri, newUri);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);
                }
            }

            return chain.filter(exchange);
        };
    }

    @Data
    public static class Config {
        /** Request header name whose value is tested against conditionPattern. */
        private String conditionHeader;
        /** Query parameter name whose value is tested against conditionPattern. */
        private String conditionParam;
        /** Java regex the header/param value must fully match. Default: .* (any value). */
        private String conditionPattern = ".*";
        /** Alternative upstream URI to route to when the condition is met. Required. */
        private String alternativeUri;
    }
}

