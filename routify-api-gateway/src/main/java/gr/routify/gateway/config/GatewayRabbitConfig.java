package gr.routify.gateway.config;

import gr.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        return new SimpleMessageConverter();
    }

    @Bean
    public RabbitTemplate gatewayRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(gatewayJsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.REPLY_TIMEOUT_MS);
        return template;
    }
}
