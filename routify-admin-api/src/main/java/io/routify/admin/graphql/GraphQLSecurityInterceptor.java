package io.routify.admin.graphql;

import io.routify.common.exception.RoutifyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * GraphQL security interceptor that enforces JWT authentication and tenant isolation
 * on all GraphQL analytics queries.
 *
 * <p>This interceptor runs after Spring Security's filter chain has already populated the
 * {@link org.springframework.security.core.context.SecurityContext}. It performs two checks:
 * <ol>
 *   <li><b>Authentication</b> — rejects unauthenticated requests.</li>
 *   <li><b>Tenant isolation</b> — stores the authenticated user's tenantId and role
 *       in the GraphQL context for downstream resolvers to enforce scoping.</li>
 * </ol>
 */
@Slf4j
@Component
public class GraphQLSecurityInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new RoutifyException.Unauthorized("Authentication required for GraphQL queries");
        }

        // Extract JWT claims once — all variables must be effectively final for use in lambdas
        final String principal = auth.getName();
        final String tenantIdStr;
        final String role;
        final boolean isSuperAdmin;

        if (auth.getDetails() instanceof io.jsonwebtoken.Claims claims) {
            Object tid = claims.get("tenantId");
            tenantIdStr = tid != null ? tid.toString() : null;
            Object r = claims.get("role");
            role = r != null ? r.toString() : null;
            isSuperAdmin = "SUPER_ADMIN".equals(role);
        } else {
            tenantIdStr = null;
            role = null;
            isSuperAdmin = false;
        }

        // Store in GraphQL context for resolvers
        final String effectiveRole = role != null ? role : "";
        request.configureExecutionInput((executionInput, builder) ->
                builder.graphQLContext(ctx -> {
                    if (tenantIdStr != null) {
                        ctx.put("authenticatedTenantId", tenantIdStr);
                    }
                    ctx.put("role", effectiveRole);
                    ctx.put("isSuperAdmin", isSuperAdmin);
                    ctx.put("principal", principal);
                }).build()
        );

        log.debug("GraphQL query authenticated: user={} tenant={} role={}",
                principal, tenantIdStr, role);

        return chain.next(request);
    }
}
