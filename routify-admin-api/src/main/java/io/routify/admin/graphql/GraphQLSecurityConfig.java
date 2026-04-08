package io.routify.admin.graphql;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GraphQL security hardening configuration (Initiative 13).
 *
 * <ul>
 *   <li><b>Max query depth: 5</b> — prevents deeply nested abuse.</li>
 *   <li><b>Max query complexity: 100</b> — prevents expensive aggregations.</li>
 * </ul>
 *
 * <p>Rate limiting for the GraphQL endpoint is handled by the existing
 * Spring Security filter chain — the {@code /api/v1/admin/graphql} path
 * is covered by the same JWT + tenant auth rules as all admin endpoints.
 *
 * <p>Spring for GraphQL auto-detects {@link Instrumentation} beans and adds them
 * to the GraphQL execution pipeline.
 */
@Configuration
public class GraphQLSecurityConfig {

    /**
     * Limits the maximum depth of a GraphQL query to 5 levels.
     * Prevents deeply nested queries that could cause excessive processing.
     */
    @Bean
    public Instrumentation maxQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(5);
    }

    /**
     * Limits query complexity to prevent expensive aggregation queries.
     * Each field contributes a complexity of 1; nested fields multiply.
     */
    @Bean
    public Instrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(100);
    }
}
