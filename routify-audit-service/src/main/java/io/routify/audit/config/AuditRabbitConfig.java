package io.routify.audit.config;

import io.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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

    // ─── AI filter decision stats + query queues ──────────────────────────────

    /** Queue: audit-service serves AI filter stats queries from admin-api */
    @Bean public Queue aiFilterStatsQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_AI_FILTER_STATS).build();
    }

    /** Queue: audit-service serves paginated AI filter decision log queries from admin-api */
    @Bean public Queue aiFilterQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_AI_FILTER_QUERY).build();
    }

    // ─── AI prompt version queues ──────────────────────────────────────────────

    @Bean public Queue aiPromptVersionsQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AI_PROMPT_VERSIONS_QUERY).build();
    }

    @Bean public Queue aiPromptVersionsGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AI_PROMPT_VERSIONS_GET).build();
    }

    @Bean public Queue aiPromptVersionsSaveQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AI_PROMPT_VERSIONS_SAVE).build();
    }

    @Bean public Queue aiDecisionLabelQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AI_DECISION_LABEL).build();
    }

    // ─── Time-series analytics queue (GraphQL Initiative 13) ────────────────

    @Bean public Queue auditTimeSeriesQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUDIT_TIME_SERIES).build();
    }

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

    @Bean
    public Binding aiFilterStatsBinding(Queue aiFilterStatsQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(aiFilterStatsQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_AI_FILTER_STATS);
    }

    @Bean
    public Binding aiFilterQueryBinding(Queue aiFilterQueryQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(aiFilterQueryQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_AI_FILTER_QUERY);
    }

    @Bean
    public Binding aiPromptVersionsQueryBinding(Queue aiPromptVersionsQueryQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(aiPromptVersionsQueryQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AI_PROMPT_VERSIONS_QUERY);
    }

    @Bean
    public Binding aiPromptVersionsGetBinding(Queue aiPromptVersionsGetQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(aiPromptVersionsGetQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AI_PROMPT_VERSIONS_GET);
    }

    @Bean
    public Binding aiPromptVersionsSaveBinding(Queue aiPromptVersionsSaveQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(aiPromptVersionsSaveQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AI_PROMPT_VERSIONS_SAVE);
    }

    @Bean
    public Binding aiDecisionLabelBinding(Queue aiDecisionLabelQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(aiDecisionLabelQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AI_DECISION_LABEL);
    }

    // ─── Time-series analytics binding (GraphQL Initiative 13) ──────────────

    @Bean
    public Binding auditTimeSeriesBinding(Queue auditTimeSeriesQueue, DirectExchange auditServiceExchange) {
        return BindingBuilder.bind(auditTimeSeriesQueue)
                .to(auditServiceExchange).with(RabbitTopology.RK_AUDIT_TIME_SERIES);
    }

    // ─── Message converter & template ─────────────────────────────────────────

    /**
     * JacksonJsonMessageConverter (Jackson 3) is used by the auto-configured listener
     * container factory. This allows @RabbitListener methods to receive and return
     * strongly-typed objects (QueryRequest subtypes, response POJOs) without manual
     * ObjectMapper calls.
     */
    @Bean
    public MessageConverter auditJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate auditRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.REPLY_TIMEOUT_MS);
        template.setObservationEnabled(true);
        return template;
    }
}

