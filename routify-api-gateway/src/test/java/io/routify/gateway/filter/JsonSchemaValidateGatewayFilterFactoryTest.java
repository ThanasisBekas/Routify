package io.routify.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JsonSchemaValidateGatewayFilterFactory}.
 *
 * <p>Covers: valid JSON against schema, invalid JSON → 400, non-JSON content-type
 * → skip, empty body → passthrough, blank schema → passthrough, malformed JSON → 400.
 */
class JsonSchemaValidateGatewayFilterFactoryTest {

    private static final String SIMPLE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer", "minimum": 0 }
              },
              "required": ["name"]
            }
            """;

    private final JsonSchemaValidateGatewayFilterFactory factory =
            new JsonSchemaValidateGatewayFilterFactory();

    private GatewayFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Test
    @DisplayName("Valid JSON against schema → passthrough")
    void validJson_passesThrough() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(SIMPLE_SCHEMA);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\": \"Alice\", \"age\": 30}"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Invalid JSON (missing required field) → 400 JSON_SCHEMA_VALIDATION_FAILED")
    void invalidJson_missingRequired_returns400() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(SIMPLE_SCHEMA);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"age\": 25}"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Invalid JSON (wrong type) → 400")
    void invalidJson_wrongType_returns400() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(SIMPLE_SCHEMA);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\": \"Alice\", \"age\": -1}"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Non-JSON content-type → skip validation, passthrough")
    void nonJsonContentType_skips() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(SIMPLE_SCHEMA);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/upload")
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("not json at all"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Empty body → passthrough (switchIfEmpty)")
    void emptyBody_passesThrough() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(SIMPLE_SCHEMA);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .build());

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Blank schema → passthrough (filter disabled)")
    void blankSchema_passesThrough() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema("");
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"anything\": true}"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Malformed JSON body → 400")
    void malformedJsonBody_returns400() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(SIMPLE_SCHEMA);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{not valid json"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Null schema → passthrough (filter disabled)")
    void nullSchema_passesThrough() {
        var config = new JsonSchemaValidateGatewayFilterFactory.Config();
        config.setSchema(null);
        GatewayFilter filter = factory.apply(config);

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"anything\": true}"));

        StepVerifier.create(filter.filter(exchange, passThroughChain()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}

