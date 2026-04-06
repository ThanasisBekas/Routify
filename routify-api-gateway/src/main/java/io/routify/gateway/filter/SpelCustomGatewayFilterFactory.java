package io.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway filter factory that evaluates a custom Spring Expression Language (SpEL)
 * expression against a rich per-request context, enabling arbitrary header
 * injection, logging, conditional short-circuit, and lightweight request mutation
 * without writing a dedicated filter factory.
 *
 * <h3>Execution context variables</h3>
 * The SpEL expression receives the following variables:
 * <ul>
 *   <li>{@code #request}  — the immutable {@link ServerHttpRequest}</li>
 *   <li>{@code #headers}  — {@code Map<String, String>} of first-value request headers</li>
 *   <li>{@code #params}   — {@code Map<String, String>} of first-value query params</li>
 *   <li>{@code #method}   — HTTP method name string, e.g. {@code "GET"}</li>
 *   <li>{@code #path}     — raw request path, e.g. {@code "/api/v1/orders"}</li>
 * </ul>
 *
 * <h3>Return value semantics</h3>
 * <ul>
 *   <li>{@code Boolean false} — short-circuits the filter chain and returns {@code 403 Forbidden}.</li>
 *   <li>Anything else (including {@code null}) — the request proceeds normally.</li>
 * </ul>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code expression} — SpEL expression to evaluate (required)</li>
 *   <li>{@code description} — optional human-readable description logged at DEBUG level</li>
 * </ul>
 *
 * <h3>Examples:</h3>
 * <pre>{@code
 * // Reject if X-Feature-Flag header is not "enabled"
 * { "expression": "#headers['X-Feature-Flag'] == 'enabled'" }
 *
 * // Always allow (no-op marker filter)
 * { "expression": "true" }
 * }</pre>
 *
 * <p>Filter type: {@code CUSTOM_SPEL}
 */
@Slf4j
@Component
public class SpelCustomGatewayFilterFactory
        extends AbstractGatewayFilterFactory<SpelCustomGatewayFilterFactory.Config> {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    public SpelCustomGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getExpression() == null || config.getExpression().isBlank()) {
            log.error("SpelCustom: 'expression' config is required but was not provided — filter disabled (pass-through)");
            return (exchange, chain) -> chain.filter(exchange);
        }

        Expression compiledExpr;
        try {
            compiledExpr = PARSER.parseExpression(config.getExpression());
        } catch (ParseException e) {
            log.error("SpelCustom: failed to compile expression '{}': {}", config.getExpression(), e.getMessage());
            return (exchange, chain) -> chain.filter(exchange);
        }

        String desc = config.getDescription() != null ? config.getDescription() : config.getExpression();

        return (exchange, chain) -> {
            log.debug("SpelCustom: evaluating — {}", desc);

            ServerHttpRequest req = exchange.getRequest();

            // Build context variables
            Map<String, String> headers = new HashMap<>();
            req.getHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) headers.put(name, values.getFirst());
            });

            Map<String, String> params = new HashMap<>();
            req.getQueryParams().forEach((name, values) -> {
                if (!values.isEmpty()) params.put(name, values.getFirst());
            });

            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("request", req);
            ctx.setVariable("headers", headers);
            ctx.setVariable("params",  params);
            ctx.setVariable("method",  req.getMethod().name());
            ctx.setVariable("path",    req.getPath().value());

            Object result;
            try {
                result = compiledExpr.getValue(ctx);
            } catch (EvaluationException e) {
                log.warn("SpelCustom: expression evaluation failed — passing through. Error: {}", e.getMessage());
                return chain.filter(exchange);
            }

            if (Boolean.FALSE.equals(result)) {
                log.debug("SpelCustom: expression returned false — rejecting request");
                return forbidden(exchange, "Request rejected by custom SpEL policy: " + desc);
            }

            return chain.filter(exchange);
        };
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String detail) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.FORBIDDEN);
        resp.getHeaders().set("Content-Type", "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Forbidden","status":403,\
                "errorCode":"CUSTOM_SPEL_REJECTED","detail":"%s"}""".formatted(
                detail.replace("\"", "\\\""));
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body.getBytes())));
    }

    @Data
    public static class Config {
        /** SpEL expression evaluated against per-request context. Required. */
        private String expression;
        /** Optional human-readable description logged at DEBUG level. */
        private String description;
    }
}

