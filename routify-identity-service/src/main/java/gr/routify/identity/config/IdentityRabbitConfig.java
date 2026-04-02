package gr.routify.identity.config;

import gr.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for routify-identity-service.
 *
 * <p>identity-service acts as the <em>responder</em> for all user and tenant queries
 * from routify-admin-api (the dashboard backend). It also handles tenant lifecycle
 * commands (create, suspend, reactivate) via synchronous RabbitMQ request/reply.
 *
 * User write commands (create/update/delete) arrive via Kafka command events
 * on {@code routify.user.commands}, consumed by {@code UserCommandKafkaConsumer}.
 */
@Configuration
public class IdentityRabbitConfig {

    @Bean
    public DirectExchange identityServiceExchange() {
        return ExchangeBuilder.directExchange(RabbitTopology.EXCHANGE_IDENTITY_SERVICE).durable(true).build();
    }

    // ─── Queues ───────────────────────────────────────────────────────────────

    @Bean
    public Queue usersQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_USERS_QUERY).build();
    }

    @Bean
    public Queue usersGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_USERS_GET).build();
    }

    @Bean
    public Queue tenantsQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_TENANTS_QUERY).build();
    }

    @Bean
    public Queue tenantsGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_TENANTS_GET).build();
    }

    @Bean
    public Queue tenantsCommandQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_TENANTS_COMMAND).build();
    }

    @Bean
    public Queue tenantsListActiveQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_TENANTS_LIST_ACTIVE).build();
    }

    @Bean
    public Queue authLoginQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUTH_LOGIN).build();
    }

    @Bean
    public Queue authRefreshQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUTH_REFRESH).build();
    }

    @Bean
    public Queue authChangePasswordQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_AUTH_CHANGE_PASSWORD).build();
    }

    @Bean
    public Queue usersChangePasswordQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_USERS_CHANGE_PASSWORD).build();
    }

    // ─── Bindings ─────────────────────────────────────────────────────────────

    @Bean
    public Binding usersQueryBinding(Queue usersQueryQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(usersQueryQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_USERS_QUERY);
    }

    @Bean
    public Binding usersGetBinding(Queue usersGetQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(usersGetQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_USERS_GET);
    }

    @Bean
    public Binding tenantsQueryBinding(Queue tenantsQueryQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(tenantsQueryQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_TENANTS_QUERY);
    }

    @Bean
    public Binding tenantsGetBinding(Queue tenantsGetQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(tenantsGetQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_TENANTS_GET);
    }

    @Bean
    public Binding tenantsCommandBinding(Queue tenantsCommandQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(tenantsCommandQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_TENANTS_COMMAND);
    }

    @Bean
    public Binding tenantsListActiveBinding(Queue tenantsListActiveQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(tenantsListActiveQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_TENANTS_LIST_ACTIVE);
    }

    @Bean
    public Binding authLoginBinding(Queue authLoginQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(authLoginQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_AUTH_LOGIN);
    }

    @Bean
    public Binding authRefreshBinding(Queue authRefreshQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(authRefreshQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_AUTH_REFRESH);
    }

    @Bean
    public Binding authChangePasswordBinding(Queue authChangePasswordQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(authChangePasswordQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_AUTH_CHANGE_PASSWORD);
    }

    @Bean
    public Binding usersChangePasswordBinding(Queue usersChangePasswordQueue, DirectExchange identityServiceExchange) {
        return BindingBuilder.bind(usersChangePasswordQueue)
                .to(identityServiceExchange).with(RabbitTopology.RK_USERS_CHANGE_PASSWORD);
    }

    // ─── Message converter & template ─────────────────────────────────────────

    /**
     * Jackson2JsonMessageConverter is used by the auto-configured listener container factory.
     * This allows @RabbitListener methods to receive and return strongly-typed objects
     * (QueryRequest subtypes, response POJOs) without manual ObjectMapper calls.
     */
    @Bean
    public MessageConverter identityJsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate identityRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setReplyTimeout(RabbitTopology.REPLY_TIMEOUT_MS);
        return template;
    }
}
