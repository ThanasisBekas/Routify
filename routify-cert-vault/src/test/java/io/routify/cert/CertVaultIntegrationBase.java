package io.routify.cert;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.cert.outbox.CertOutboxPoller;
import io.routify.cert.repository.CertGroupRepository;
import io.routify.cert.repository.CertOutboxEventRepository;
import io.routify.cert.repository.ProcessedCommandRepository;
import io.routify.cert.repository.StoredCertificateRepository;
import io.routify.cert.service.CertEncryptionService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Shared Testcontainers base class for routify-cert-vault integration tests.
 *
 * <p>Starts PostgreSQL + Kafka + RabbitMQ containers once per JVM (singleton pattern).
 * Provides shared fixtures, cleanup, and a Kafka test consumer for verifying published events.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
abstract class CertVaultIntegrationBase {

    // ─── Shared test constants ────────────────────────────────────────────────

    static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    static final String ACTOR = "test-user";

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
    protected StoredCertificateRepository certRepository;

    @Autowired
    protected CertGroupRepository groupRepository;

    @Autowired
    protected CertOutboxEventRepository outboxEventRepository;

    @Autowired
    protected ProcessedCommandRepository processedCommandRepository;

    @Autowired
    protected CertOutboxPoller outboxPoller;

    @Autowired
    protected CertEncryptionService encryptionService;

    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    @BeforeEach
    void cleanDatabase() {
        // Clean in dependency order to avoid FK violations
        jdbcTemplate.execute("DELETE FROM routify_cert.outbox_event");
        jdbcTemplate.execute("DELETE FROM routify_cert.processed_command");
        jdbcTemplate.execute("DELETE FROM routify_cert.acme_order");
        jdbcTemplate.execute("DELETE FROM routify_cert.stored_certificate");
        jdbcTemplate.execute("DELETE FROM routify_cert.cert_group");
        jdbcTemplate.execute("DELETE FROM routify_cert.acme_account");
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
        kafkaTemplate.send(topic, TENANT_ID.toString(), command).get();
    }
}

