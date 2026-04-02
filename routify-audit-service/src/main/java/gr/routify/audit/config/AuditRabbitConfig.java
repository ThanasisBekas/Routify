package gr.routify.audit.config;

import gr.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for routify-audit-service.
 *
 * <p>audit-service acts as the <em>responder</em> for audit log and request log
 * queries from routify-admin-api (the dashboard backend).
 *
 * <p>All audit data is immutable — only query handlers are needed, no write commands.
 */
@Configuration
public class AuditRabbitConfig {

    @Bean
    public DirectExchange auditServiceExchange() {
        return ExchangeBuilder.directExchange(RabbitTopology.EXCHANGE_AUDIT_SERVICE).durable(true).build();
    }

    // ─── Queues ───────────────────────────────────────────────────────────────

    @Bean
    public Queue auditEventsQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_EVENTS_QUERY).build();
    }

    @Bean
    public Queue auditRequestsQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REQUESTS_QUERY).build();
    }

    @Bean
    public Queue auditRequestsStatsQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REQUESTS_STATS).build();
    }

    @Bean public Queue auditReplayFailedQueryQueue()  { return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REPLAY_FAILED_QUERY).build(); }
    @Bean public Queue auditReplayPendingQueryQueue() { return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REPLAY_PENDING_QUERY).build(); }
    @Bean public Queue auditReplayStatsQueue()        { return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REPLAY_STATS).build(); }
    @Bean public Queue auditReplaySingleQueue()       { return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REPLAY_SINGLE).build(); }
    @Bean public Queue auditReplayBulkQueue()         { return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_REPLAY_BULK).build(); }

    // ─── Bindings ─────────────────────────────────────────────────────────────

    @Bean
    public Binding auditEventsQueryBinding(Queue auditEventsQueryQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditEventsQueryQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_EVENTS_QUERY);
    }

    @Bean
    public Binding auditRequestsQueryBinding(Queue auditRequestsQueryQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditRequestsQueryQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REQUESTS_QUERY);
    }

    @Bean
    public Binding auditRequestsStatsBinding(Queue auditRequestsStatsQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditRequestsStatsQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REQUESTS_STATS);
    }

    @Bean
    public Binding auditReplayFailedQueryBinding(Queue auditReplayFailedQueryQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditReplayFailedQueryQueue).to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REPLAY_FAILED_QUERY);
    }

    @Bean
    public Binding auditReplayPendingQueryBinding(Queue auditReplayPendingQueryQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditReplayPendingQueryQueue).to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REPLAY_PENDING_QUERY);
    }

    @Bean
    public Binding auditReplayStatsBinding(Queue auditReplayStatsQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditReplayStatsQueue).to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REPLAY_STATS);
    }

    @Bean
    public Binding auditReplaySingleBinding(Queue auditReplaySingleQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditReplaySingleQueue).to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REPLAY_SINGLE);
    }

    @Bean
    public Binding auditReplayBulkBinding(Queue auditReplayBulkQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditReplayBulkQueue).to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_REPLAY_BULK);
    }

    // ─── Message converter & template ─────────────────────────────────────────

    /**
     * SimpleMessageConverter is used by the auto-configured listener container factory.
     * This ensures that incoming JSON payloads are passed as raw Strings to the
     * {@code @RabbitListener} methods, which perform their own Jackson parsing.
     * (Using Jackson2JsonMessageConverter here would cause it to try to deserialize
     * the JSON object directly into a String, resulting in a MismatchedInputException.)
     */
    @Bean
    public MessageConverter auditJsonMessageConverter() {
        return new SimpleMessageConverter();
    }

    @Bean
    public RabbitTemplate auditRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.REPLY_TIMEOUT_MS);
        return template;
    }
}

