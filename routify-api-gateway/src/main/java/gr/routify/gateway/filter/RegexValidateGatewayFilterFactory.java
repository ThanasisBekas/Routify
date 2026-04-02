package gr.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Gateway filter factory that validates a request header or query parameter
 * against a regular expression pattern, rejecting non-matching requests with
 * a {@code 400 Bad Request}.
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code source}  — where to read the value from: {@code HEADER} (default) or {@code QUERY}</li>
 *   <li>{@code field}   — the header name or query param name to validate (required)</li>
 *   <li>{@code pattern} — the Java regex pattern the value must fully match (required)</li>
 *   <li>{@code required} — if {@code true} (default) a missing field is also rejected; if
 *       {@code false} a missing field passes through and only a present-but-invalid value rejects</li>
 * </ul>
 *
 * <p>Filter type: {@code VALIDATE_REGEX}
 */
@Slf4j
@Component
public class RegexValidateGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RegexValidateGatewayFilterFactory.Config> {

    public RegexValidateGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getField() == null || config.getField().isBlank()) {
            log.error("RegexValidate: 'field' config is required but was not provided");
            return (exchange, chain) -> chain.filter(exchange);
        }
        if (config.getPattern() == null || config.getPattern().isBlank()) {
            log.error("RegexValidate: 'pattern' config is required but was not provided");
            return (exchange, chain) -> chain.filter(exchange);
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile(config.getPattern());
        } catch (PatternSyntaxException e) {
            log.error("RegexValidate: invalid regex pattern '{}': {}", config.getPattern(), e.getMessage());
            return (exchange, chain) -> chain.filter(exchange);
        }

        String source  = config.getSource()   != null ? config.getSource().toUpperCase()  : "HEADER";
        String field   = config.getField();
        boolean required = config.isRequired();

        return (exchange, chain) -> {
            String value = "QUERY".equals(source)
                    ? exchange.getRequest().getQueryParams().getFirst(field)
                    : exchange.getRequest().getHeaders().getFirst(field);

            if (value == null || value.isBlank()) {
                if (required) {
                    log.debug("RegexValidate: required {} '{}' is missing", source, field);
                    return badRequest(exchange,
                            "Required %s '%s' is missing".formatted(source.toLowerCase(), field));
                }
                return chain.filter(exchange); // optional and absent — pass through
            }

            if (!compiled.matcher(value).matches()) {
                log.debug("RegexValidate: {} '{}' value '{}' does not match pattern '{}'",
                        source, field, value, config.getPattern());
                return badRequest(exchange,
                        "%s '%s' has an invalid format".formatted(source.toLowerCase(), field));
            }

            return chain.filter(exchange);
        };
    }

    private Mono<Void> badRequest(ServerWebExchange exchange, String detail) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.BAD_REQUEST);
        resp.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        String body = """
                {"type":"about:blank","title":"Bad Request","status":400,\
                "detail":"%s"}""".formatted(detail.replace("\"", "\\\""));
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Data
    public static class Config {
        /** HEADER (default) or QUERY — where to read the value. */
        private String  source   = "HEADER";
        /** Header name or query param name to validate. Required. */
        private String  field;
        /** Java regex pattern the value must fully match. Required. */
        private String  pattern;
        /** If true (default), a missing field is rejected. */
        private boolean required = true;
    }
}

