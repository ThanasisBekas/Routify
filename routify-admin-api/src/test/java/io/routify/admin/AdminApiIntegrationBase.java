package io.routify.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.event.RabbitTopology;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared Testcontainers base class for routify-admin-api integration tests.
 *
 * <p>Starts Kafka + RabbitMQ + Redis containers once per JVM (singleton pattern).
 * Provides MockMvc, JWT generation, Kafka test consumer, and RabbitMQ mock-service
 * reply infrastructure.
 *
 * <h3>Architecture under test</h3>
 * <p>admin-api is a <em>BFF</em> with no database. It:
 * <ul>
 *   <li><b>Writes:</b> HTTP → Kafka command published (async, returns 202)</li>
 *   <li><b>Reads:</b> HTTP → RabbitMQ RPC → mock service reply → HTTP response</li>
 * </ul>
 *
 * <p>Tests register mock RabbitMQ listeners that intercept requests on
 * downstream service exchanges and send back canned JSON responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
abstract class AdminApiIntegrationBase {

    // ─── Shared test constants ────────────────────────────────────────────────

    static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    static final UUID USER_ID   = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final String ACTOR   = "test-admin";

    // ─── Testcontainers (singleton — shared across all test classes) ───────────

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // RabbitMQ
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    // ─── Shared test consumer for verifying Kafka commands ────────────────────

    private static KafkaConsumer<String, String> testConsumer;

    static KafkaConsumer<String, String> getTestConsumer() {
        if (testConsumer == null) {
            testConsumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "admin-api-test-verifier-" + UUID.randomUUID(),
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true"
            ));
        }
        return testConsumer;
    }

    @AfterAll
    static void closeTestConsumer() {
        if (testConsumer != null) {
            testConsumer.close();
            testConsumer = null;
        }
    }

    // ─── Spring-managed beans ─────────────────────────────────────────────────

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ConnectionFactory connectionFactory;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    // ─── Mock RabbitMQ reply listeners ────────────────────────────────────────

    /**
     * Tracks active mock listener containers so they can be stopped in teardown.
     * Key: "exchange:routingKey" → active container.
     */
    private final Map<String, SimpleMessageListenerContainer> mockListeners = new ConcurrentHashMap<>();

    @BeforeEach
    void tearDownMockListeners() {
        mockListeners.values().forEach(SimpleMessageListenerContainer::stop);
        mockListeners.clear();
    }

    /**
     * Registers a mock RabbitMQ reply listener that intercepts requests on the
     * given exchange + routing key and replies with a JSON response.
     *
     * <p>This simulates a downstream service (e.g. routify-route-service) replying
     * to an RPC query from admin-api.
     *
     * @param exchange    Target exchange (e.g. {@link RabbitTopology#EXCHANGE_ROUTE_SERVICE})
     * @param routingKey  Routing key (e.g. {@link RabbitTopology#RK_ROUTES_QUERY})
     * @param replyBuilder Function that takes the request body and returns the JSON reply.
     *                     Return value is serialised to JSON bytes and sent back via replyTo.
     */
    protected void mockRabbitReply(String exchange, String routingKey,
                                   Function<String, Object> replyBuilder) {
        String key = exchange + ":" + routingKey;
        // Stop any existing listener for the same key
        var existing = mockListeners.remove(key);
        if (existing != null) existing.stop();

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);

        // Declare exchange + queue + binding
        DirectExchange ex = new DirectExchange(exchange, true, false);
        admin.declareExchange(ex);

        String queueName = exchange + ".test." + routingKey.replace(".", "-");
        Queue queue = new Queue(queueName, false, false, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(ex).with(routingKey));

        // Set up listener that replies to each message
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setMessageListener(message -> {
            try {
                String requestBody = new String(message.getBody(), StandardCharsets.UTF_8);
                Object replyPayload = replyBuilder.apply(requestBody);
                String replyJson = objectMapper.writeValueAsString(replyPayload);

                MessageProperties replyProps = new MessageProperties();
                replyProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                replyProps.setCorrelationId(message.getMessageProperties().getCorrelationId());

                Message reply = new Message(replyJson.getBytes(StandardCharsets.UTF_8), replyProps);
                rabbitTemplate.send(
                        "",  // default exchange
                        message.getMessageProperties().getReplyTo(),
                        reply);
            } catch (Exception e) {
                throw new RuntimeException("Mock reply failed for " + key, e);
            }
        });
        container.start();

        mockListeners.put(key, container);
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    /**
     * Drains all records from the given Kafka topic within the timeout.
     */
    protected List<ConsumerRecord<String, String>> drainTopic(String topic, Duration timeout) {
        var consumer = getTestConsumer();
        consumer.subscribe(List.of(topic));

        List<ConsumerRecord<String, String>> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
            records.forEach(results::add);
            if (!results.isEmpty()) break;
        }

        consumer.unsubscribe();
        return results;
    }

    /**
     * Generates an unsigned JWT token for test authentication.
     *
     * <p>The admin-api's {@code JwtAuthFilter} operates in dev mode when no public key
     * is configured (test profile sets {@code routify.jwt.public-key:} to empty).
     * In dev mode it Base64-decodes the payload segment without signature verification.
     *
     * @param userId   User UUID
     * @param tenantId Tenant UUID
     * @param role     Role string (e.g. "SUPER_ADMIN", "OPERATOR")
     * @param username Username for the subject
     * @return Bearer token string (without "Bearer " prefix)
     */
    protected static String generateTestJwt(UUID userId, UUID tenantId, String role, String username) {
        // Build an unsigned JWT with the claims the JwtAuthFilter expects.
        // The dev-mode parser reads claims from the Base64-decoded payload segment.
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        claims.put("tenantId", tenantId.toString());
        claims.put("role", role);
        claims.put("email", username + "@test.routify.io");
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());

        // Produce a three-part "JWT" with an unsigned payload.
        // Header: {"alg":"none","typ":"JWT"}
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsBytes(claims));
            return header + "." + payload + ".";
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test JWT", e);
        }
    }

    /**
     * Convenience: generates a SUPER_ADMIN JWT for the shared test user/tenant.
     */
    protected static String superAdminJwt() {
        return generateTestJwt(USER_ID, TENANT_ID, "SUPER_ADMIN", ACTOR);
    }

    /**
     * Convenience: generates an OPERATOR JWT for the shared test user/tenant.
     */
    protected static String operatorJwt() {
        return generateTestJwt(USER_ID, TENANT_ID, "OPERATOR", ACTOR);
    }

    /**
     * Convenience: generates a VIEWER JWT for the shared test user/tenant.
     */
    protected static String viewerJwt() {
        return generateTestJwt(USER_ID, TENANT_ID, "VIEWER", ACTOR);
    }
}

