package gr.routify.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.identity.outbox.IdentityOutboxEventRepository;
import gr.routify.identity.outbox.IdentityOutboxPoller;
import gr.routify.identity.repository.ProcessedCommandRepository;
import gr.routify.identity.repository.TenantRepository;
import gr.routify.identity.repository.UserRepository;
import gr.routify.identity.security.JwtService;
import gr.routify.identity.service.AuthService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Testcontainers base class for routify-identity-service integration tests.
 *
 * <p>Starts PostgreSQL + Kafka + RabbitMQ + Redis containers once per JVM (singleton pattern).
 * Provides shared fixtures, cleanup, MockMvc, and a Kafka test consumer for verifying events.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
abstract class IdentityServiceIntegrationBase {

    // ─── Shared test constants ────────────────────────────────────────────────

    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_PASSWORD = "testpassword123";
    static final String PLATFORM_SLUG  = "platform";

    // ─── Testcontainers (singleton — shared across all test classes) ───────────

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("routify")
            .withUsername("routify")
            .withPassword("test");

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
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

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

    // ─── Shared test consumer for verifying Kafka events ──────────────────────

    private static KafkaConsumer<String, String> testConsumer;

    static KafkaConsumer<String, String> getTestConsumer() {
        if (testConsumer == null) {
            testConsumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "test-verifier-" + UUID.randomUUID(),
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
    protected UserRepository userRepository;

    @Autowired
    protected TenantRepository tenantRepository;

    @Autowired
    protected IdentityOutboxEventRepository outboxEventRepository;

    @Autowired
    protected ProcessedCommandRepository processedCommandRepository;

    @Autowired
    protected IdentityOutboxPoller outboxPoller;

    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected AuthService authService;

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    @BeforeEach
    void cleanDatabase() {
        // Clean in dependency order — preserve tenant and admin (seeded by DataSeeder)
        jdbcTemplate.execute("DELETE FROM routify_identity.outbox_event");
        jdbcTemplate.execute("DELETE FROM routify_identity.processed_command");

        // Flush Redis blocklist keys
        var keys = redisTemplate.keys("routify:token:blocklist:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    /**
     * Drains all records from the given Kafka topics within the timeout.
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
     * Sends a CommandEvent as JSON to a Kafka topic.
     */
    protected void sendCommand(String topic, Object command) throws Exception {
        kafkaTemplate.send(topic, PLATFORM_SLUG, command).get();
    }
}

