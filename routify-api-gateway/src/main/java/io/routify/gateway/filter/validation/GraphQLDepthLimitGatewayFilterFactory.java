package io.routify.gateway.filter.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.InvalidSyntaxException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Gateway filter that parses incoming GraphQL queries and rejects those exceeding
 * configurable depth, complexity, or alias limits. Optionally blocks introspection
 * queries and batched queries beyond a maximum batch size.
 *
 * <p>Uses {@code graphql-java} for AST parsing only (no execution engine). The parser
 * is lazy-initialized on first GraphQL request to avoid startup latency.
 *
 * <p>Non-GraphQL requests (non-POST, non-JSON content type, or bodies without a
 * {@code query} field) pass through unchanged.
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code routify.filter.graphql.rejected} — requests rejected for exceeding limits</li>
 *   <li>{@code routify.filter.graphql.analyzed} — requests successfully analyzed</li>
 * </ul>
 *
 * <p>Filter type: {@code GRAPHQL_DEPTH_LIMIT}
 */
@Slf4j
@Component
public class GraphQLDepthLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<GraphQLDepthLimitGatewayFilterFactory.Config> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 1_048_576; // 1 MB max GraphQL body

    private final MeterRegistry meterRegistry;

    public GraphQLDepthLimitGatewayFilterFactory(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        Counter rejected = Counter.builder("routify.filter.graphql.rejected")
                .description("GraphQL queries rejected for exceeding limits")
                .register(meterRegistry);
        Counter analyzed = Counter.builder("routify.filter.graphql.analyzed")
                .description("GraphQL queries successfully analyzed")
                .register(meterRegistry);

        return new GraphQLDepthLimitFilter(config, rejected, analyzed);
    }

    // ─── Inner filter ─────────────────────────────────────────────────────────

    static class GraphQLDepthLimitFilter implements GatewayFilter, Ordered {

        private final Config config;
        private final Counter rejected;
        private final Counter analyzed;

        GraphQLDepthLimitFilter(Config config, Counter rejected, Counter analyzed) {
            this.config = config;
            this.rejected = rejected;
            this.analyzed = analyzed;
        }

        @Override
        public int getOrder() {
            // Run early, before body transformation filters
            return Ordered.HIGHEST_PRECEDENCE + 200;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            ServerHttpRequest request = exchange.getRequest();

            // Only process POST requests with JSON content type
            if (request.getMethod() != HttpMethod.POST) {
                return chain.filter(exchange);
            }
            MediaType contentType = request.getHeaders().getContentType();
            if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return chain.filter(exchange);
            }

            // Read the request body
            return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                    .flatMap(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        String body = new String(bytes, StandardCharsets.UTF_8);

                        return processBody(exchange, chain, body, bytes);
                    })
                    .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
        }

        private Mono<Void> processBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                         String body, byte[] rawBytes) {
            try {
                // Try to parse as JSON array (batched query) or single object
                body = body.trim();
                if (body.startsWith("[")) {
                    return processBatchedQuery(exchange, chain, body, rawBytes);
                } else {
                    return processSingleQuery(exchange, chain, body, rawBytes);
                }
            } catch (Exception e) {
                log.debug("GraphQLDepthLimit: body is not valid JSON — passing through");
                return forwardWithBody(exchange, chain, rawBytes);
            }
        }

        private Mono<Void> processSingleQuery(ServerWebExchange exchange, GatewayFilterChain chain,
                                                String body, byte[] rawBytes) {
            Map<String, Object> jsonBody;
            try {
                jsonBody = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.debug("GraphQLDepthLimit: invalid JSON body — passing through");
                return forwardWithBody(exchange, chain, rawBytes);
            }

            Object queryObj = jsonBody.get("query");
            if (!(queryObj instanceof String query) || query.isBlank()) {
                // Not a GraphQL request — pass through
                return forwardWithBody(exchange, chain, rawBytes);
            }

            // Analyze the GraphQL query
            GraphQLQueryAnalyzer.AnalysisResult result;
            try {
                result = GraphQLQueryAnalyzer.analyze(query);
            } catch (InvalidSyntaxException e) {
                rejected.increment();
                return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("GRAPHQL_PARSE_ERROR")
                        .detail("Invalid GraphQL query: %s", e.getMessage())
                        .write(exchange);
            }

            // Check limits
            String violation = checkLimits(result, config);
            if (violation != null) {
                rejected.increment();
                log.info("GraphQLDepthLimit: query rejected — {}", violation);
                return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("GRAPHQL_QUERY_TOO_COMPLEX")
                        .detail(violation)
                        .write(exchange);
            }

            analyzed.increment();
            log.debug("GraphQLDepthLimit: query allowed (depth={} complexity={} aliases={})",
                    result.depth(), result.complexity(), result.aliasCount());
            return forwardWithBody(exchange, chain, rawBytes);
        }

        private Mono<Void> processBatchedQuery(ServerWebExchange exchange, GatewayFilterChain chain,
                                                 String body, byte[] rawBytes) {
            List<Map<String, Object>> batch;
            try {
                batch = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.debug("GraphQLDepthLimit: invalid JSON array — passing through");
                return forwardWithBody(exchange, chain, rawBytes);
            }

            // Check batch size
            if (batch.size() > config.getMaxBatchSize()) {
                rejected.increment();
                log.info("GraphQLDepthLimit: batch size {} exceeds limit {}", batch.size(), config.getMaxBatchSize());
                return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                        .errorCode("GRAPHQL_BATCH_TOO_LARGE")
                        .detail("Batched query contains %d operations, max allowed is %d",
                                batch.size(), config.getMaxBatchSize())
                        .write(exchange);
            }

            // Analyze each operation
            for (int i = 0; i < batch.size(); i++) {
                Map<String, Object> op = batch.get(i);
                Object queryObj = op.get("query");
                if (!(queryObj instanceof String query) || query.isBlank()) {
                    continue; // Not a GraphQL operation — skip
                }

                GraphQLQueryAnalyzer.AnalysisResult result;
                try {
                    result = GraphQLQueryAnalyzer.analyze(query);
                } catch (InvalidSyntaxException e) {
                    rejected.increment();
                    return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                            .errorCode("GRAPHQL_PARSE_ERROR")
                            .detail("Invalid GraphQL query in batch operation %d: %s", i, e.getMessage())
                            .write(exchange);
                }

                String violation = checkLimits(result, config);
                if (violation != null) {
                    rejected.increment();
                    log.info("GraphQLDepthLimit: batch operation {} rejected — {}", i, violation);
                    return GatewayProblemResponse.status(HttpStatus.BAD_REQUEST)
                            .errorCode("GRAPHQL_QUERY_TOO_COMPLEX")
                            .detail("Batch operation %d: %s", i, violation)
                            .write(exchange);
                }
            }

            analyzed.increment();
            return forwardWithBody(exchange, chain, rawBytes);
        }

        // ─── Limit checking ───────────────────────────────────────────────────

        static String checkLimits(GraphQLQueryAnalyzer.AnalysisResult result, Config config) {
            if (result.depth() > config.getMaxDepth()) {
                return "Query depth %d exceeds maximum allowed depth %d"
                        .formatted(result.depth(), config.getMaxDepth());
            }
            if (result.complexity() > config.getMaxComplexity()) {
                return "Query complexity %d exceeds maximum allowed complexity %d"
                        .formatted(result.complexity(), config.getMaxComplexity());
            }
            if (result.aliasCount() > config.getMaxAliases()) {
                return "Query uses %d aliases, maximum allowed is %d"
                        .formatted(result.aliasCount(), config.getMaxAliases());
            }
            if (result.hasIntrospection() && !config.isIntrospectionAllowed()) {
                return "Introspection queries (__schema, __type) are not allowed";
            }
            return null;
        }

        // ─── Body re-emission ─────────────────────────────────────────────────

        private Mono<Void> forwardWithBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                            byte[] bodyBytes) {
            ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public Flux<DataBuffer> getBody() {
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bodyBytes);
                    return Flux.just(buffer);
                }
            };
            return chain.filter(exchange.mutate().request(decoratedRequest).build());
        }
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    @Data
    public static class Config {
        /** Maximum allowed query depth. Default: 10. */
        private int maxDepth = 10;
        /** Maximum query complexity score (1 per field). Default: 100. */
        private int maxComplexity = 100;
        /** Maximum number of aliases per query. Default: 5. */
        private int maxAliases = 5;
        /** Whether __schema / __type introspection queries are allowed. Default: false. */
        private boolean introspectionAllowed = false;
        /** Maximum number of operations in a batched query. Default: 5. */
        private int maxBatchSize = 5;
    }
}

