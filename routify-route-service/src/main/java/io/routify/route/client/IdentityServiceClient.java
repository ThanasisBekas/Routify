package io.routify.route.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.client.AmqpServiceClientSupport;
import io.routify.common.domain.TenantPlan;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RabbitMQ RPC client for calling routify-identity-service from routify-route-service.
 *
 * <p>Used for:
 * <ul>
 *   <li>Bulk TenantPlanCache warmup at startup</li>
 *   <li>On-demand single-tenant plan fetch on cache miss</li>
 * </ul>
 */
@Slf4j
@Component
public class IdentityServiceClient extends AmqpServiceClientSupport {

    public IdentityServiceClient(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper, RabbitTopology.EXCHANGE_IDENTITY_SERVICE, "identity-service");
    }

    /**
     * Fetches all active tenant → plan mappings from identity-service.
     */
    public QueryResponse.TenantPlansList fetchTenantPlans() {
        return rpc(RabbitTopology.RK_TENANT_PLANS,
                new QueryRequest.TenantPlansQuery(),
                QueryResponse.TenantPlansList.class);
    }

    /**
     * Fetches a single tenant's plan from identity-service via RabbitMQ RPC.
     *
     * @param tenantId the tenant to look up
     * @return the tenant's plan, or {@code null} if the tenant doesn't exist or the RPC fails
     */
    public TenantPlan fetchTenantPlan(UUID tenantId) {
        try {
            QueryResponse.TenantDetail detail = rpc(
                    RabbitTopology.RK_TENANTS_GET,
                    new QueryRequest.TenantGet(tenantId),
                    QueryResponse.TenantDetail.class);
            if (detail != null && detail.plan() != null) {
                log.debug("Fetched plan for tenant {}: {}", tenantId, detail.plan());
                return detail.plan();
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to fetch plan for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}

