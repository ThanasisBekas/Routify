package io.routify.cert.config;

import io.routify.common.event.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for routify-cert-vault.
 *
 * <p>cert-vault acts as the <em>responder</em> for all synchronous certificate queries:
 * <ul>
 *   <li>Paginated list queries from routify-admin-api</li>
 *   <li>Single-cert GET queries from routify-admin-api</li>
 *   <li>Active certificates list for gateway config picker</li>
 *   <li>Gateway TLS snapshot for routify-api-gateway</li>
 *   <li>Vault statistics from routify-admin-api dashboard</li>
 * </ul>
 */
@Configuration
public class CertVaultRabbitConfig {

    // ─── Exchange ─────────────────────────────────────────────────────────────

    @Bean
    public DirectExchange certVaultExchange() {
        return ExchangeBuilder
                .directExchange(RabbitTopology.EXCHANGE_CERT_VAULT)
                .durable(true)
                .build();
    }

    // ─── Queues ───────────────────────────────────────────────────────────────

    @Bean
    public Queue certsQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERTS_QUERY).build();
    }

    @Bean
    public Queue certsGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERTS_GET).build();
    }

    @Bean
    public Queue certsActiveListQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERTS_ACTIVE_LIST).build();
    }

    @Bean
    public Queue certsStatsQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERTS_STATS).build();
    }

    @Bean
    public Queue certsGatewaySnapshotQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERTS_GATEWAY_SNAPSHOT).build();
    }

    @Bean
    public Queue certsFetchMaterialQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERTS_FETCH_MATERIAL).build();
    }

    @Bean
    public Queue certGroupsQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERT_GROUPS_QUERY).build();
    }

    @Bean
    public Queue certGroupsGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERT_GROUPS_GET).build();
    }

    @Bean
    public Queue certGroupsMembersQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_CERT_GROUPS_MEMBERS).build();
    }

    // ─── ACME Queues ──────────────────────────────────────────────────────────

    @Bean
    public Queue acmeRegisterQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ACME_REGISTER).build();
    }

    @Bean
    public Queue acmeIssueQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ACME_ISSUE).build();
    }

    @Bean
    public Queue acmeOrdersQueryQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ACME_ORDERS_QUERY).build();
    }

    @Bean
    public Queue acmeOrderGetQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ACME_ORDER_GET).build();
    }

    @Bean
    public Queue acmeRenewQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_ACME_RENEW).build();
    }

    // ─── Bindings ─────────────────────────────────────────────────────────────

    @Bean
    public Binding certsQueryBinding(Queue certsQueryQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certsQueryQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERTS_QUERY);
    }

    @Bean
    public Binding certsGetBinding(Queue certsGetQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certsGetQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERTS_GET);
    }

    @Bean
    public Binding certsActiveListBinding(Queue certsActiveListQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certsActiveListQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERTS_ACTIVE_LIST);
    }

    @Bean
    public Binding certsStatsBinding(Queue certsStatsQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certsStatsQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERTS_STATS);
    }

    @Bean
    public Binding certsGatewaySnapshotBinding(Queue certsGatewaySnapshotQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certsGatewaySnapshotQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERTS_GATEWAY_SNAPSHOT);
    }

    @Bean
    public Binding certsFetchMaterialBinding(Queue certsFetchMaterialQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certsFetchMaterialQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERTS_FETCH_MATERIAL);
    }

    @Bean
    public Binding certGroupsQueryBinding(Queue certGroupsQueryQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certGroupsQueryQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERT_GROUPS_QUERY);
    }

    @Bean
    public Binding certGroupsGetBinding(Queue certGroupsGetQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certGroupsGetQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERT_GROUPS_GET);
    }

    @Bean
    public Binding certGroupsMembersBinding(Queue certGroupsMembersQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(certGroupsMembersQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_CERT_GROUPS_MEMBERS);
    }

    // ─── ACME Bindings ────────────────────────────────────────────────────────

    @Bean
    public Binding acmeRegisterBinding(Queue acmeRegisterQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(acmeRegisterQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_ACME_REGISTER);
    }

    @Bean
    public Binding acmeIssueBinding(Queue acmeIssueQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(acmeIssueQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_ACME_ISSUE);
    }

    @Bean
    public Binding acmeOrdersQueryBinding(Queue acmeOrdersQueryQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(acmeOrdersQueryQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_ACME_ORDERS_QUERY);
    }

    @Bean
    public Binding acmeOrderGetBinding(Queue acmeOrderGetQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(acmeOrderGetQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_ACME_ORDER_GET);
    }

    @Bean
    public Binding acmeRenewBinding(Queue acmeRenewQueue, DirectExchange certVaultExchange) {
        return BindingBuilder.bind(acmeRenewQueue)
                .to(certVaultExchange).with(RabbitTopology.RK_ACME_RENEW);
    }

    // ─── Message converter & template ─────────────────────────────────────────

    /**
     * JacksonJsonMessageConverter (Jackson 3) is used by the auto-configured listener
     * container factory. This allows @RabbitListener methods to receive and return
     * strongly-typed objects (QueryRequest subtypes, response POJOs) without manual
     * ObjectMapper calls.
     */
    @Bean
    public MessageConverter certVaultMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate certVaultRabbitTemplate(ConnectionFactory connectionFactory) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(certVaultMessageConverter());
        return template;
    }
}
