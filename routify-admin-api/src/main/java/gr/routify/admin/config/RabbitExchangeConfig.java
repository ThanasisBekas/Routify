package gr.routify.admin.config;

import gr.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * RabbitMQ exchange declarations and template configuration for routify-admin-api.
 *
 * <p>admin-api is a <em>requester</em> only — it does not declare any queues or consume
 * any messages via RabbitMQ. It only declares the exchanges it sends to, so that AMQP
 * channel setup succeeds even if the target service hasn't started yet.
 *
 * <p>The actual queues are declared and bound by the services that own them:
 * <ul>
 *   <li>{@code routify.route-service} exchange + queues → declared by routify-route-service</li>
 *   <li>{@code routify.identity-service} exchange + queues → declared by routify-identity-service</li>
 *   <li>{@code routify.audit-service} exchange + queues → declared by routify-audit-service</li>
 *   <li>{@code routify.gateway} exchange + queue → declared by routify-api-gateway</li>
 *   <li>{@code routify.cert-vault} exchange + queues → declared by routify-cert-vault</li>
 * </ul>
 */
@Configuration
public class RabbitExchangeConfig {

    /**
     * Reply timeout (ms) for all synchronous RabbitMQ RPC calls.
     * If a downstream service does not reply within this window the call
     * returns {@code null} rather than blocking indefinitely, allowing the
     * circuit-breaker fallback to fire.
     */
    private static final long REPLY_TIMEOUT_MS = 5_000L;

    /**
     * Primary {@link RabbitTemplate} with a bounded reply timeout.
     *
     * <p>Sets {@code replyTimeout} to {@value #REPLY_TIMEOUT_MS} ms so that a
     * slow or absent downstream service never stalls an admin-api thread longer
     * than 5 seconds. The Resilience4j circuit breakers declared in
     * {@code application.yml} will open after repeated timeouts.
     */
    @Bean
    @Primary
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setReplyTimeout(REPLY_TIMEOUT_MS);
        return template;
    }

    @Bean
    public DirectExchange routeServiceExchangeRef() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_ROUTE_SERVICE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange identityServiceExchangeRef() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_IDENTITY_SERVICE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange auditServiceExchangeRef() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_AUDIT_SERVICE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange gatewayExchangeRef() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_GATEWAY)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange certVaultExchangeRef() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_CERT_VAULT)
                .durable(true)
                .build();
    }
}
