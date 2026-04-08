package io.routify.gateway.filter;

import io.routify.common.event.KafkaTopics;
import io.routify.common.web.RoutifyHeaders;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gateway filter factory that evaluates a custom Spring Expression Language (SpEL)
 * expression against a sandboxed per-request context, enabling conditional
 * short-circuit, lightweight request policy checks, and audited evaluation.
 *
 * <h3>Security model</h3>
 * Uses {@link SimpleEvaluationContext} (read-only data binding with instance methods)
 * which disallows type references ({@code T(java.lang.Runtime)}), constructors
 * ({@code new ProcessBuilder()}), and method invocation on arbitrary objects.
 * Only String instance methods are allowed on context variables.
 *
 * <h3>Execution context variables</h3>
 * <ul>
 *   <li>{@code #headers}     — {@code Map<String, String>} first-value request headers</li>
 *   <li>{@code #params}      — {@code Map<String, String>} first-value query params</li>
 *   <li>{@code #method}      — HTTP method name string, e.g. {@code "GET"}</li>
 *   <li>{@code #path}        — raw request path, e.g. {@code "/api/v1/orders"}</li>
 *   <li>{@code #contentType} — {@code Content-Type} header value or empty string</li>
 *   <li>{@code #clientIp}    — resolved client IP (X-Forwarded-For aware)</li>
 * </ul>
 *
 * <p><strong>Note:</strong> The {@code #request} variable has been removed for security
 * reasons. Use {@code #clientIp} instead of {@code #request.remoteAddress} and
 * {@code #contentType} instead of {@code #request.headers.contentType}.
 *
 * <h3>Return value semantics</h3>
 * <ul>
 *   <li>{@code Boolean false} — short-circuits the filter chain and returns {@code 403 Forbidden}.</li>
 *   <li>Anything else (including {@code null}) — the request proceeds normally.</li>
 * </ul>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code expression}          — SpEL expression (required)</li>
 *   <li>{@code description}         — human-readable label (optional)</li>
 *   <li>{@code maxExpressionLength} — max expression chars (default 500)</li>
 *   <li>{@code maxPropertyDepth}    — max nested property accessors (default 5)</li>
 *   <li>{@code allowedFunctions}    — restrict callable methods (optional)</li>
 * </ul>
 *
 * <p>Filter type: {@code CUSTOM_SPEL}
 */
@Slf4j
@Component
public class SpelCustomGatewayFilterFactory
        extends AbstractGatewayFilterFactory<SpelCustomGatewayFilterFactory.Config> {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final int DEFAULT_MAX_EXPRESSION_LENGTH = 500;
    private static final int DEFAULT_MAX_PROPERTY_DEPTH = 5;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SpelCustomGatewayFilterFactory(KafkaTemplate<String, Object> kafkaTemplate) {
        super(Config.class);
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getExpression() == null || config.getExpression().isBlank()) {
            log.error("SpelCustom: 'expression' config is required but was not provided — filter disabled (pass-through)");
            return (exchange, chain) -> chain.filter(exchange);
        }

        // ── Expression complexity limits (validated at config bind time) ──────
        int maxLen = config.getMaxExpressionLength() > 0
                ? config.getMaxExpressionLength() : DEFAULT_MAX_EXPRESSION_LENGTH;
        int maxDepth = config.getMaxPropertyDepth() > 0
                ? config.getMaxPropertyDepth() : DEFAULT_MAX_PROPERTY_DEPTH;

        String expr = config.getExpression();
        if (expr.length() > maxLen) {
            log.error("SpelCustom: expression length ({}) exceeds maxExpressionLength ({}) — filter disabled",
                    expr.length(), maxLen);
            return (exchange, chain) -> GatewayProblemResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("SPEL_EXPRESSION_TOO_LONG")
                    .detail("SpEL expression exceeds maximum length of %d characters", maxLen)
                    .write(exchange);
        }

        int depth = countPropertyDepth(expr);
        if (depth > maxDepth) {
            log.error("SpelCustom: expression property depth ({}) exceeds maxPropertyDepth ({}) — filter disabled",
                    depth, maxDepth);
            return (exchange, chain) -> GatewayProblemResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("SPEL_EXPRESSION_TOO_COMPLEX")
                    .detail("SpEL expression exceeds maximum property depth of %d", maxDepth)
                    .write(exchange);
        }

        Expression compiledExpr;
        try {
            compiledExpr = PARSER.parseExpression(expr);
        } catch (ParseException e) {
            log.error("SpelCustom: failed to compile expression '{}': {}", expr, e.getMessage());
            return (exchange, chain) -> chain.filter(exchange);
        }

        String desc = config.getDescription() != null ? config.getDescription() : expr;

        // Pre-compute the allowed functions set once at config bind time (not per request)
        List<String> allowedFunctions = config.getAllowedFunctions();
        boolean hasAllowedFunctions = allowedFunctions != null && !allowedFunctions.isEmpty();
        Set<String> allowedMethodNames = hasAllowedFunctions
                ? Set.copyOf(allowedFunctions) : Set.of();

        if (hasAllowedFunctions) {
            log.info("SpelCustom: method restriction enabled — allowed methods: {}", allowedMethodNames);
        }

        return (exchange, chain) -> {
            log.debug("SpelCustom: evaluating — {}", desc);
            long evalStart = System.nanoTime();

            ServerHttpRequest req = exchange.getRequest();

            // Build context variables — all primitives and immutable types only
            Map<String, String> headers = new HashMap<>();
            req.getHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) headers.put(name, values.getFirst());
            });

            Map<String, String> params = new HashMap<>();
            req.getQueryParams().forEach((name, values) -> {
                if (!values.isEmpty()) params.put(name, values.getFirst());
            });

            MediaType contentType = req.getHeaders().getContentType();
            String contentTypeStr = contentType != null ? contentType.toString() : "";

            String clientIp = Optional.ofNullable(req.getHeaders().getFirst("X-Forwarded-For"))
                    .orElseGet(() -> Optional.ofNullable(req.getRemoteAddress())
                            .map(InetSocketAddress::getHostString).orElse("unknown"));

            // Build a fresh SimpleEvaluationContext per request with the variables.
            // When allowedFunctions is configured, use a restricting MethodResolver
            // that only permits explicitly listed method names.
            SimpleEvaluationContext ctx;
            if (hasAllowedFunctions) {
                ctx = SimpleEvaluationContext
                        .forReadOnlyDataBinding()
                        .withMethodResolvers(new AllowedMethodResolver(allowedMethodNames))
                        .build();
            } else {
                ctx = SimpleEvaluationContext
                        .forReadOnlyDataBinding()
                        .withInstanceMethods()
                        .build();
            }
            ctx.setVariable("headers", headers);
            ctx.setVariable("params", params);
            ctx.setVariable("method", req.getMethod().name());
            ctx.setVariable("path", req.getPath().value());
            ctx.setVariable("contentType", contentTypeStr);
            ctx.setVariable("clientIp", clientIp);

            Object result;
            try {
                result = compiledExpr.getValue(ctx);
            } catch (EvaluationException e) {
                long evalMs = (System.nanoTime() - evalStart) / 1_000_000;
                log.warn("SpelCustom: expression evaluation failed — passing through. Error: {}", e.getMessage());
                publishAuditEvent(exchange, expr, null, evalMs, clientIp);
                return chain.filter(exchange);
            }

            long evalMs = (System.nanoTime() - evalStart) / 1_000_000;
            boolean resultBool = !Boolean.FALSE.equals(result);

            // ── Audit event (fire-and-forget) ──────────────────────────────
            publishAuditEvent(exchange, expr, resultBool, evalMs, clientIp);

            if (Boolean.FALSE.equals(result)) {
                log.debug("SpelCustom: expression returned false — rejecting request");
                return forbidden(exchange, "Request rejected by custom SpEL policy: " + desc);
            }

            return chain.filter(exchange);
        };
    }

    // ─── Property depth counting ─────────────────────────────────────────────

    /**
     * Counts the maximum nested property accessor depth in a SpEL expression.
     * Scans for chains of {@code .xxx} segments not inside string literals.
     */
    static int countPropertyDepth(String expression) {
        int maxDepth = 0;
        int currentDepth = 0;
        boolean inString = false;
        char quoteChar = 0;

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);

            // Track string literals to avoid counting dots inside them
            if ((c == '\'' || c == '"') && (i == 0 || expression.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    quoteChar = c;
                } else if (c == quoteChar) {
                    inString = false;
                }
                continue;
            }
            if (inString) continue;

            if (c == '.') {
                currentDepth++;
                maxDepth = Math.max(maxDepth, currentDepth);
            } else if (c == ' ' || c == '(' || c == ')' || c == '[' || c == ']'
                    || c == ',' || c == '&' || c == '|') {
                currentDepth = 0;
            }
        }
        return maxDepth;
    }

    // ─── Audit event publishing ──────────────────────────────────────────────

    private void publishAuditEvent(ServerWebExchange exchange, String expression,
                                   Boolean result, long evaluationTimeMs, String clientIp) {
        try {
            String correlationId = exchange.getRequest().getHeaders()
                    .getFirst(RoutifyHeaders.CORRELATION_ID);

            Route matchedRoute = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = null;
            if (matchedRoute != null) {
                String fullId = matchedRoute.getId();
                int sep = fullId.indexOf("::");
                routeId = sep >= 0 ? fullId.substring(sep + 2) : fullId;
            }

            // Truncate expression for audit payload (max 200 chars)
            String truncatedExpr = expression.length() > 200
                    ? expression.substring(0, 200) + "..." : expression;

            Map<String, Object> auditPayload = Map.of(
                    "type", "CUSTOM_SPEL_EVALUATED",
                    "routeId", routeId != null ? routeId : "unknown",
                    "expression", truncatedExpr,
                    "result", result != null ? result.toString() : "error",
                    "evaluationTimeMs", evaluationTimeMs,
                    "clientIp", clientIp,
                    "correlationId", correlationId != null ? correlationId : "",
                    "timestamp", Instant.now().toString()
            );

            String key = correlationId != null ? correlationId : UUID.randomUUID().toString();
            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS, key, auditPayload);
        } catch (Exception e) {
            log.debug("SpelCustom: failed to publish audit event: {}", e.getMessage());
        }
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String detail) {
        return GatewayProblemResponse.status(HttpStatus.FORBIDDEN)
                .errorCode("CUSTOM_SPEL_REJECTED")
                .detail(detail)
                .write(exchange);
    }

    // ─── Allowed method resolver ────────────────────────────────────────────

    /**
     * Custom {@link MethodResolver} that delegates to {@link ReflectiveMethodResolver}
     * but only permits methods whose names are in the configured allowed set.
     * When a method is not in the allow-list, {@code resolve()} returns {@code null},
     * which causes SpEL to throw an {@link EvaluationException} at evaluation time.
     */
    private static class AllowedMethodResolver implements MethodResolver {

        private final Set<String> allowedMethodNames;
        private final ReflectiveMethodResolver delegate = new ReflectiveMethodResolver();

        AllowedMethodResolver(Set<String> allowedMethodNames) {
            this.allowedMethodNames = allowedMethodNames;
        }

        @Override
        public MethodExecutor resolve(org.springframework.expression.EvaluationContext context,
                                      Object targetObject, String name,
                                      List<TypeDescriptor> argumentTypes) throws EvaluationException {
            if (!allowedMethodNames.contains(name)) {
                log.debug("SpelCustom: method '{}' blocked by allowedFunctions policy", name);
                return null;
            }
            try {
                return delegate.resolve(context, targetObject, name, argumentTypes);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** SpEL expression evaluated against per-request context. Required. */
        private String expression;
        /** Optional human-readable description logged at DEBUG level. */
        private String description;
        /**
         * Maximum allowed expression length in characters. Default: 500.
         * Expressions exceeding this limit are rejected at config bind time.
         */
        private int maxExpressionLength = DEFAULT_MAX_EXPRESSION_LENGTH;
        /**
         * Maximum allowed nested property accessor depth. Default: 5.
         * Expressions exceeding this limit are rejected at config bind time.
         */
        private int maxPropertyDepth = DEFAULT_MAX_PROPERTY_DEPTH;
        /**
         * Optional list of allowed String method names. When non-empty, only
         * listed methods are permitted in the expression.
         * Default: empty (all String methods allowed — backward compatible).
         */
        private List<String> allowedFunctions = List.of();
    }
}

