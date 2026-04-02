package gr.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Outbound filter — injects a {@code Basic} {@code Authorization} header built from
 * route-level {@code username}/{@code password} args into every downstream request.
 *
 * <p>Use this for filter type {@code DOWNSTREAM_BASIC_AUTH}: the gateway authenticates
 * itself to the upstream service on behalf of the caller.
 *
 * @see BasicAuthGatewayFilterFactory for inbound Basic Auth validation
 */
@Component
@Slf4j
public class DownstreamBasicAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<DownstreamBasicAuthGatewayFilterFactory.Config>
        implements Ordered {

    public DownstreamBasicAuthGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String username = config.getUsername();
            String password = config.getPassword();

            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                log.error("Missing required credentials in DOWNSTREAM_BASIC_AUTH filter config");
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Gateway misconfiguration: downstream Basic auth credentials are not set"));
            }

            log.debug("Injecting downstream Basic Authorization header for user '{}'", username);

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("Authorization", buildBasicHeader(username, password))
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    private String buildBasicHeader(String username, String password) {
        String plain = "%s:%s".formatted(username, password);
        return "Basic " + Base64.getEncoder()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Data
    public static class Config {
        private String username;
        private String password;
    }
}

