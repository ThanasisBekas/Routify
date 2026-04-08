package io.routify.gateway.filter;

import io.routify.gateway.auth.properties.ClientProperties;
import io.routify.gateway.auth.properties.NameValuesConfig;
import io.routify.gateway.filter.shared.GatewayProblemResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AbstractNameValueGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Inbound auth filter — validates {@code X-Client-Id} header and injects
 * {@code organization-id} into the downstream request.
 * Returns 401 on mismatch.
 *
 * <h3>Config sources (dual-path)</h3>
 * <ul>
 *   <li><strong>Dynamic (recommended):</strong> Link the filter to a gateway config
 *       auth provider of type {@code CLIENT_ID} via a {@code gatewayConfigRef} with
 *       {@code refType=CLIENT_ID_MAPPING}. The {@link io.routify.gateway.routing.GatewayConfigRefResolver}
 *       resolves the provider's {@code clientEntries} into the {@code values} list and
 *       passes through the {@code clientIdMapping} (orgId→clientId) map.</li>
 *   <li><strong>Legacy (static YAML):</strong> Configure the {@code values} list
 *       directly in the filter's JSONB config and define {@code clientIdMapping}
 *       in the static {@code client.clientIdMapping} YAML properties. Backward-compatible
 *       with existing deployments that use {@link ClientProperties} binding.</li>
 * </ul>
 */
@Slf4j
@Component
public class ClientIdAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ClientIdAuthGatewayFilterFactory.Config> implements Ordered {

    private final ClientProperties clientProperties;

    public ClientIdAuthGatewayFilterFactory(ClientProperties clientProperties) {
        super(Config.class);
        this.clientProperties = clientProperties;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            var clientIdNameValue = matchClientId(config, exchange.getRequest());
            if (clientIdNameValue == null) {
                log.error("Missing or invalid ClientId. Request Headers: {}",
                        exchange.getRequest().getHeaders());
                return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
                        .errorCode("INVALID_CLIENT_ID")
                        .detail("Missing or invalid client id header")
                        .write(exchange);
            }

            // Resolve clientIdMapping: prefer dynamic config from gatewayConfigRef,
            // fall back to static ClientProperties YAML
            Map<String, String> effectiveMapping = config.getClientIdMapping() != null
                    && !config.getClientIdMapping().isEmpty()
                    ? config.getClientIdMapping()
                    : clientProperties.getClientIdMapping();

            if (effectiveMapping.isEmpty()) {
                log.warn("No clientIdMapping configured — organization-id will be null for clientId='{}'",
                        clientIdNameValue.getValue());
            }

            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header("organization-id",
                            resolveOrganizationId(effectiveMapping, clientIdNameValue))
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        };
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private static AbstractNameValueGatewayFilterFactory.NameValueConfig matchClientId(
            Config config, ServerHttpRequest request) {
        return config.getValues().stream()
                .filter(f -> f.getValue().equals(request.getHeaders().getFirst(f.getName())))
                .findFirst()
                .orElse(null);
    }

    private static String resolveOrganizationId(
            Map<String, String> clientIdMapping,
            AbstractNameValueGatewayFilterFactory.NameValueConfig clientIdNameValue) {
        return clientIdMapping.entrySet().stream()
                .filter(f -> f.getValue().equals(clientIdNameValue.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * Extended config class that adds an optional {@code clientIdMapping} field.
     * When the filter is linked to a {@code CLIENT_ID} auth provider via
     * {@code gatewayConfigRef}, the resolver populates this map from the
     * provider's {@code clientIdMapping}. Falls back to static
     * {@link ClientProperties#getClientIdMapping()} when empty or null.
     */
    @lombok.Getter
    @lombok.Setter
    public static class Config extends NameValuesConfig {

        private Map<String, String> clientIdMapping;
    }
}

