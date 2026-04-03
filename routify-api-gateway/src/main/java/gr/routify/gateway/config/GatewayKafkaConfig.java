package gr.routify.gateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Kafka configuration for routify-api-gateway.
 *
 * <p>Uses MANUAL_IMMEDIATE acknowledgment for at-least-once delivery.
 * Failed records go to {@code <topic>.DLQ} after exponential back-off.
 */
@EnableKafka
@Configuration
public class GatewayKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, Object> gatewayConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,                 "routify-gateway",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "false"
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(gatewayConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setRecordMessageConverter(new StringJsonMessageConverter(kafkaObjectMapper()));
        factory.setConcurrency(3);
        // C5: Dead-Letter Queue — failed records go to <topic>.DLQ after 30s back-off
        factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate));
        return factory;
    }

    // ─── Producer ────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, Object> gatewayProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,     StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,   JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG,                     "1",
                ProducerConfig.RETRIES_CONFIG,                  3,
                ProducerConfig.LINGER_MS_CONFIG,                5,
                ProducerConfig.COMPRESSION_TYPE_CONFIG,         "snappy"
        ));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(gatewayProducerFactory());
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /**
     * ObjectMapper with {@link JavaTimeModule} for correct {@link java.time.Instant}
     * deserialization inside {@link StringJsonMessageConverter}.
     */
    private ObjectMapper kafkaObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
