package io.routify.gateway.config;

import io.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * RabbitMQ topology for routify-api-gateway.
 *
 * <p>The gateway:
 * <ul>
 *   <li><b>Owns</b>: {@code routify.gateway} exchange + {@code routify.gateway.status} queue
 *       (serves status requests from admin-api)</li>
 *   <li><b>Sends to</b>: {@code routify.route-service} exchange (declared here as a ref;
 *       actual queue declarations are owned by route-service)</li>
 *   <li><b>Sends to</b>: {@code routify.cert-vault} exchange (declared here as a ref;
 *       actual queue declarations are owned by cert-vault)</li>
 *   <li><b>Sends to</b>: {@code routify.ai-service} exchange (declared here as a ref;
 *       actual queue declarations are owned by ai-service) — AI filter RPC</li>
 * </ul>
 */
@Configuration
public class GatewayRabbitConfig {

    @Bean
    public DirectExchange gatewayExchange() {
        return ExchangeBuilder.directExchange(RabbitTopology.EXCHANGE_GATEWAY).durable(true).build();
    }

    /** Reference — actual queues declared by route-service (idempotent in RabbitMQ). */
    @Bean
    public DirectExchange routeServiceExchangeRef() {
        return ExchangeBuilder.directExchange(RabbitTopology.EXCHANGE_ROUTE_SERVICE).durable(true).build();
    }

    /** Reference — actual queues declared by cert-vault (idempotent in RabbitMQ). */
    @Bean
    public DirectExchange certVaultExchangeRef() {
        return ExchangeBuilder.directExchange(RabbitTopology.EXCHANGE_CERT_VAULT).durable(true).build();
    }

    /**
     * Reference — actual queues declared by ai-service.
     * Idempotent: declaring an exchange that already exists is a no-op in RabbitMQ
     * as long as the properties (durable, type) match.
     */
    @Bean
    public DirectExchange aiServiceExchangeRef() {
        return ExchangeBuilder.directExchange(RabbitTopology.EXCHANGE_AI_SERVICE).durable(true).build();
    }

    @Bean
    public Queue gatewayStatusQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_GATEWAY_STATUS).build();
    }

    @Bean
    public Binding gatewayStatusBinding(Queue gatewayStatusQueue, DirectExchange gatewayExchange) {
        return BindingBuilder.bind(gatewayStatusQueue)
                .to(gatewayExchange)
                .with(RabbitTopology.RK_GATEWAY_STATUS_REQUEST);
    }

    /** Queue: serves live in-memory CertificateRegistry snapshots to admin-api */
    @Bean
    public Queue gatewayCertRegistryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_GATEWAY_CERT_REGISTRY).build();
    }

    @Bean
    public Binding gatewayCertRegistryBinding(Queue gatewayCertRegistryQueue, DirectExchange gatewayExchange) {
        return BindingBuilder.bind(gatewayCertRegistryQueue)
                .to(gatewayExchange)
                .with(RabbitTopology.RK_GATEWAY_CERT_REGISTRY);
    }

    @Bean
    public MessageConverter gatewayJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /**
     * Primary RabbitTemplate — used for route snapshot + gateway status calls.
     * Timeout: {@link RabbitTopology#REPLY_TIMEOUT_MS} (10s).
     */
    @Primary
    @Bean
    public RabbitTemplate gatewayRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(gatewayJsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.REPLY_TIMEOUT_MS);
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * Dedicated RabbitTemplate for AI filter RPC calls.
     *
     * <p>Uses a <strong>tighter timeout</strong> ({@link RabbitTopology#AI_FILTER_REPLY_TIMEOUT_MS}
     * = 3.5s) compared to the default 10s. This ensures the gateway filter falls back
     * to the configured {@code fallbackAction} promptly when the AI service is degraded,
     * preventing tail-latency cascades on the critical request path.
     *
     * <p>A separate template instance is required because {@link RabbitTemplate} is not
     * thread-safe for concurrent {@code sendAndReceive} calls with different timeouts.
     * Each template manages its own Direct Reply-To correlation map.
     */
    @Bean(name = "aiServiceRabbitTemplate")
    public RabbitTemplate aiServiceRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(gatewayJsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.AI_FILTER_REPLY_TIMEOUT_MS);
        template.setObservationEnabled(true);
        return template;
    }
}
