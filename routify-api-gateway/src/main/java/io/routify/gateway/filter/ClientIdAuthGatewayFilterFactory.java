package io.routify.gateway.filter;

import io.routify.gateway.auth.properties.ClientProperties;
import io.routify.gateway.auth.properties.NameValuesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AbstractNameValueGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Inbound auth filter — validates {@code X-Client-Id} header and injects
 * {@code organization-id} into the downstream request.
 * Returns 401 on mismatch.
 */
@Slf4j
@Component
public class ClientIdAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<NameValuesConfig> implements Ordered {

    private final ClientProperties clientProperties;

    public ClientIdAuthGatewayFilterFactory(ClientProperties clientProperties) {
        super(NameValuesConfig.class);
        this.clientProperties = clientProperties;
    }

    @Override
    public GatewayFilter apply(NameValuesConfig config) {
        return (exchange, chain) -> {
            var clientIdNameValue = matchClientId(config, exchange.getRequest());
            if (clientIdNameValue == null) {
                log.error("Missing or invalid ClientId. Request Headers: {}",
                        exchange.getRequest().getHeaders());
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Missing or invalid client id header"));
            }

            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header("organization-id",
                            resolveOrganizationId(clientProperties.getClientIdMapping(), clientIdNameValue))
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        };
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private static AbstractNameValueGatewayFilterFactory.NameValueConfig matchClientId(
            NameValuesConfig config, ServerHttpRequest request) {
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
}

