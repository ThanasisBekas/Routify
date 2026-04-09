package io.routify.route.config;

import io.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for routify-route-service.
 *
 * <p>route-service acts as the <em>responder</em> for all synchronous queries:
 * <ul>
 *   <li>Gateway snapshot requests from routify-api-gateway</li>
 *   <li>Gateway config GET/SAVE requests from routify-admin-api</li>
 *   <li>Route stats requests from routify-admin-api</li>
 * </ul>
 *
 * <p>All queues are durable (survive broker restarts), bound to a single
 * {@code routify.route-service} direct exchange.
 */
@Configuration
public class RabbitConfig {

    // ─── Exchange ─────────────────────────────────────────────────────────────

    @Bean
    public DirectExchange routeServiceExchange() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_ROUTE_SERVICE)
                .durable(true)
                .build();
    }

    // ─── Queues ───────────────────────────────────────────────────────────────

    @Bean
    public Queue gatewaySnapshotQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTE_GATEWAY_SNAPSHOT).build();
    }

    @Bean
    public Queue gatewayConfigGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_GATEWAY_CONFIG_GET).build();
    }

    @Bean
    public Queue gatewayConfigSaveQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_GATEWAY_CONFIG_SAVE).build();
    }

    @Bean
    public Queue routeStatsQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTE_STATS).build();
    }

    // ─── Bindings ─────────────────────────────────────────────────────────────

    @Bean
    public Binding gatewaySnapshotBinding(Queue gatewaySnapshotQueue,
                                          DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(gatewaySnapshotQueue)
                .to(routeServiceExchange)
                .with(RabbitTopology.RK_ROUTE_GATEWAY_SNAPSHOT);
    }

    @Bean
    public Binding gatewayConfigGetBinding(Queue gatewayConfigGetQueue,
                                           DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(gatewayConfigGetQueue)
                .to(routeServiceExchange)
                .with(RabbitTopology.RK_GATEWAY_CONFIG_GET);
    }

    @Bean
    public Binding gatewayConfigSaveBinding(Queue gatewayConfigSaveQueue,
                                            DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(gatewayConfigSaveQueue)
                .to(routeServiceExchange)
                .with(RabbitTopology.RK_GATEWAY_CONFIG_SAVE);
    }

    @Bean
    public Binding routeStatsBinding(Queue routeStatsQueue,
                                     DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(routeStatsQueue)
                .to(routeServiceExchange)
                .with(RabbitTopology.RK_ROUTE_STATS);
    }

    // ─── Admin-API query queues (routes + filters) ────────────────────────────

    @Bean
    public Queue routesQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTES_QUERY).build();
    }

    @Bean
    public Queue routesGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTES_GET).build();
    }

    @Bean
    public Queue routesCloneQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTES_CLONE).build();
    }

    @Bean
    public Queue filtersQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_FILTERS_QUERY).build();
    }

    @Bean
    public Queue filtersGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_FILTERS_GET).build();
    }

    @Bean
    public Binding routesQueryBinding(Queue routesQueryQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(routesQueryQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_ROUTES_QUERY);
    }

    @Bean
    public Binding routesGetBinding(Queue routesGetQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(routesGetQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_ROUTES_GET);
    }

    @Bean
    public Binding routesCloneBinding(Queue routesCloneQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(routesCloneQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_ROUTES_CLONE);
    }

    @Bean
    public Binding filtersQueryBinding(Queue filtersQueryQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(filtersQueryQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_FILTERS_QUERY);
    }

    @Bean
    public Binding filtersGetBinding(Queue filtersGetQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(filtersGetQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_FILTERS_GET);
    }

    @Bean
    public Queue filtersDeprecatedUsageQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_FILTERS_DEPRECATED_USAGE).build();
    }

    @Bean
    public Binding filtersDeprecatedUsageBinding(Queue filtersDeprecatedUsageQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(filtersDeprecatedUsageQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_FILTERS_DEPRECATED_USAGE);
    }

    // ─── Route SLO queues ──────────────────────────────────────────────────────

    @Bean
    public Queue routeSloGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTE_SLO_GET).build();
    }

    @Bean
    public Queue routeSloSaveQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ROUTE_SLO_SAVE).build();
    }

    @Bean
    public Binding routeSloGetBinding(Queue routeSloGetQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(routeSloGetQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_ROUTE_SLO_GET);
    }

    @Bean
    public Binding routeSloSaveBinding(Queue routeSloSaveQueue, DirectExchange routeServiceExchange) {
        return BindingBuilder.bind(routeSloSaveQueue)
                .to(routeServiceExchange).with(RabbitTopology.RK_ROUTE_SLO_SAVE);
    }

    // ─── Message converter & template ─────────────────────────────────────────

    /**
     * JacksonJsonMessageConverter (Jackson 3) is used by the auto-configured listener
     * container factory. This allows @RabbitListener methods to receive and return
     * strongly-typed objects (QueryRequest subtypes, response POJOs) without manual
     * ObjectMapper calls.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.REPLY_TIMEOUT_MS);
        template.setObservationEnabled(true);
        return template;
    }
}
