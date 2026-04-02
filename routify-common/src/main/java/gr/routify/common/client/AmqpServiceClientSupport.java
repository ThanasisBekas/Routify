package gr.routify.common.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.QueryRequest;
import gr.routify.common.event.QueryResponse;
import gr.routify.common.event.RabbitTopology;
import gr.routify.common.exception.RoutifyException;
import gr.routify.common.web.RoutifyHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for all Routify service messaging clients that communicate
 * over RabbitMQ using the Direct Reply-To (request/reply) RPC pattern.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Component
 * public class RouteServiceClient extends AmqpServiceClientSupport {
 *
 *     public RouteServiceClient(RabbitTemplate rabbit, ObjectMapper mapper) {
 *         super(rabbit, mapper, RabbitTopology.EXCHANGE_ROUTE_SERVICE, "route-service");
 *     }
 *
 *     public PageResponse<RouteDto> queryRoutes(RouteQuery req) {
 *         return rpc(RabbitTopology.RK_ROUTES_QUERY, req, new TypeReference<>() {});
 *     }
 * }
 * }</pre>
 *
 * <h2>What this class handles automatically</h2>
 * <ul>
 *   <li>JSON serialisation of the request body via Jackson</li>
 *   <li>{@code Content-Type: application/json} header</li>
 *   <li>{@code X-From-Service} header identifying the calling service</li>
 *   <li>{@code X-Correlation-Id} header propagated from the active
 *       {@link gr.routify.common.security.SecurityContext} when available</li>
 *   <li>Null-reply detection (broker timeout) → {@link RoutifyException.GatewayError}</li>
 *   <li>Typed deserialisation of the response via a {@link TypeReference}</li>
 * </ul>
 */
@Slf4j
public abstract class AmqpServiceClientSupport {

    protected final RabbitTemplate rabbitTemplate;
    protected final ObjectMapper   objectMapper;

    /** RabbitMQ exchange this client sends to. */
    private final String exchange;

    /** Logical service name used in {@code X-From-Service} header and log messages. */
    private final String serviceName;

    /**
     * @param rabbitTemplate Spring AMQP template (must be pre-configured with reply timeout).
     * @param objectMapper   Jackson mapper shared across the application context.
     * @param exchange       The target RabbitMQ direct exchange (see {@link RabbitTopology}).
     * @param serviceName    Logical name of the <em>calling</em> service (e.g. {@code "admin-api"}).
     */
    protected AmqpServiceClientSupport(RabbitTemplate rabbitTemplate,
                                       ObjectMapper objectMapper,
                                       String exchange,
                                       String serviceName) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper   = objectMapper;
        this.exchange       = exchange;
        this.serviceName    = serviceName;
    }

    // ─── RPC (request / reply) ────────────────────────────────────────────────

    /**
     * Sends a JSON-serialised {@code requestBody} to {@code routingKey} on this client's exchange
     * and deserialises the reply into {@code T}.
     *
     * @param routingKey  RabbitMQ routing key (see {@link RabbitTopology} {@code RK_*} constants).
     * @param requestBody Object that will be serialised to JSON. Use {@code Map.of()} for empty bodies.
     * @param responseType Jackson {@link TypeReference} for the expected response type.
     * @param <T>         Desired response type.
     * @return Deserialised response.
     * @throws RoutifyException.GatewayError if the downstream service does not reply within the timeout.
     */
    protected final <T> T rpc(String routingKey, Object requestBody, TypeReference<T> responseType) {
        try {
            Message request  = buildRequest(requestBody);
            Message response = rabbitTemplate.sendAndReceive(exchange, routingKey, request);
            if (response == null) {
                throw new RoutifyException.GatewayError(
                        "No reply from %s (exchange=%s, rk=%s) — timeout or service down"
                                .formatted(serviceName, exchange, routingKey));
            }
            String json = new String(response.getBody(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, responseType);
        } catch (RoutifyException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] RPC failed — exchange={} rk={}: {}", serviceName, exchange, routingKey, e.getMessage(), e);
            throw new RoutifyException.GatewayError(
                    "RPC call to %s failed: %s".formatted(serviceName, e.getMessage()), e);
        }
    }

    /**
     * Convenience overload that deserialises the reply into a {@code Map<String, Object>}.
     * Useful for loosely-typed or ad-hoc queries.
     */
    protected final Map<String, Object> rpc(String routingKey, Object requestBody) {
        return rpc(routingKey, requestBody, new TypeReference<>() {});
    }

    /**
     * Typed overload — serialises a strongly-typed {@link QueryRequest} and sends it as an
     * RPC call, deserialising the reply into {@code Map<String, Object>}.
     *
     * <p>This is the primary overload for query calls. The {@code "type"} discriminator
     * embedded by Jackson makes the wire format self-describing so the handler can
     * validate it received the expected request type.
     *
     * @param routingKey Routing key (see {@link gr.routify.common.event.RabbitTopology}).
     * @param request    Strongly-typed query request record.
     */
    protected final Map<String, Object> rpc(String routingKey, QueryRequest request) {
        return rpc(routingKey, (Object) request);
    }

    /**
     * Typed overload — serialises a strongly-typed {@link QueryRequest} and sends it as an
     * RPC call, deserialising the reply into {@code T} using the supplied {@link TypeReference}.
     */
    protected final <T> T rpc(String routingKey, QueryRequest request, TypeReference<T> responseType) {
        return rpc(routingKey, (Object) request, responseType);
    }

    /**
     * Typed overload — serialises a strongly-typed {@link CommandEvent} (sync RabbitMQ command,
     * e.g. {@link CommandEvent.CreateTenant}) and sends it as an RPC call, deserialising
     * the reply into {@code Map<String, Object>}.
     */
    protected final Map<String, Object> rpc(String routingKey, CommandEvent command) {
        return rpc(routingKey, (Object) command);
    }

    /**
     * Typed overload — sends a {@link QueryRequest} and deserialises the reply into
     * the specified {@link QueryResponse} subtype.
     *
     * @param routingKey   Routing key (see {@link RabbitTopology}).
     * @param request      Strongly-typed query request record.
     * @param responseType Concrete {@link QueryResponse} subtype class.
     * @param <R>          Expected response type (must implement {@link QueryResponse}).
     */
    protected final <R extends QueryResponse> R rpc(String routingKey,
                                                     QueryRequest request,
                                                     Class<R> responseType) {
        try {
            Message reply = rabbitTemplate.sendAndReceive(exchange, routingKey, buildRequest(request));
            if (reply == null) {
                throw new gr.routify.common.exception.RoutifyException.GatewayError(
                        "No reply from %s (exchange=%s, rk=%s) — timeout or service down"
                                .formatted(serviceName, exchange, routingKey));
            }
            String json = new String(reply.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            return objectMapper.readValue(json, responseType);
        } catch (gr.routify.common.exception.RoutifyException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] RPC failed — exchange={} rk={}: {}", serviceName, exchange, routingKey, e.getMessage(), e);
            throw new gr.routify.common.exception.RoutifyException.GatewayError(
                    "RPC call to %s failed: %s".formatted(serviceName, e.getMessage()), e);
        }
    }

    /**
     * Typed overload — sends a {@link CommandEvent} and deserialises the reply into
     * the specified {@link QueryResponse} subtype.
     */
    protected final <R extends QueryResponse> R rpc(String routingKey,
                                                     CommandEvent command,
                                                     Class<R> responseType) {
        try {
            Message reply = rabbitTemplate.sendAndReceive(exchange, routingKey, buildRequest(command));
            if (reply == null) {
                throw new gr.routify.common.exception.RoutifyException.GatewayError(
                        "No reply from %s (exchange=%s, rk=%s) — timeout or service down"
                                .formatted(serviceName, exchange, routingKey));
            }
            String json = new String(reply.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            return objectMapper.readValue(json, responseType);
        } catch (gr.routify.common.exception.RoutifyException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] RPC failed — exchange={} rk={}: {}", serviceName, exchange, routingKey, e.getMessage(), e);
            throw new gr.routify.common.exception.RoutifyException.GatewayError(
                    "RPC call to %s failed: %s".formatted(serviceName, e.getMessage()), e);
        }
    }

    // ─── Fire-and-forget ──────────────────────────────────────────────────────

    /**
     * Sends a JSON-serialised {@code requestBody} to {@code routingKey} without waiting for a reply.
     * Suitable for one-way notifications or commands where the result is delivered asynchronously.
     *
     * @param routingKey  RabbitMQ routing key.
     * @param requestBody Object that will be serialised to JSON.
     */
    protected final void send(String routingKey, Object requestBody) {
        try {
            Message request = buildRequest(requestBody);
            rabbitTemplate.send(exchange, routingKey, request);
            log.debug("[{}] Message sent — exchange={} rk={}", serviceName, exchange, routingKey);
        } catch (Exception e) {
            log.error("[{}] send failed — exchange={} rk={}: {}", serviceName, exchange, routingKey, e.getMessage(), e);
            throw new RoutifyException.GatewayError(
                    "Failed to send message to %s: %s".formatted(serviceName, e.getMessage()), e);
        }
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /** Returns the exchange this client is bound to. */
    protected String exchange() { return exchange; }

    /** Returns the logical service name of this client. */
    protected String serviceName() { return serviceName; }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Message buildRequest(Object requestBody) throws Exception {
        String json = objectMapper.writeValueAsString(requestBody);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader(RabbitTopology.HEADER_FROM_SERVICE, serviceName);
        props.setHeader(RoutifyHeaders.CORRELATION_ID, resolveCorrelationId());

        return MessageBuilder
                .withBody(json.getBytes(StandardCharsets.UTF_8))
                .andProperties(props)
                .build();
    }

    /**
     * Attempts to read the correlation ID from the active {@link gr.routify.common.security.SecurityContext}.
     * Falls back to a freshly generated UUID when no security context is present
     * (e.g. scheduled jobs, system-initiated calls).
     */
    private String resolveCorrelationId() {
        try {
            return gr.routify.common.security.SecurityContext.current().correlationId();
        } catch (IllegalStateException ignored) {
            return UUID.randomUUID().toString();
        }
    }
}
