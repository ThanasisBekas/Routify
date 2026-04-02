package gr.routify.cert.config;

import gr.routify.common.kafka.KafkaDlqErrorHandlerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

/**
 * Kafka configuration for routify-cert-vault.
 *
 * <p>Provides:
 * <ul>
 *   <li>Producer: for the Outbox poller to publish cert domain events</li>
 *   <li>Consumer: for command events from routify-admin-api ({@code routify.cert.commands})</li>
 * </ul>
 * Failed records go to {@code <topic>.DLQ} after exponential back-off.
 */
@EnableKafka
@Configuration
public class CertVaultKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ─── Producer ─────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, String> certProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,          bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,       StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,     StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,                       "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,         "true",
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5",
                ProducerConfig.RETRIES_CONFIG,                    "3"
        ));
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(certProducerFactory());
    }

    // ─── Consumer (command topics from admin-api) ─────────────────────────────

    @Bean
    public ConsumerFactory<String, String> certCommandConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,          bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,     StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,          "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,         "false"
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> certCommandKafkaListenerContainerFactory(
            KafkaTemplate<String, String> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(certCommandConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(2);
        // C5: Dead-Letter Queue — failed records go to <topic>.DLQ after 30s back-off
        factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate));
        return factory;
    }
}

