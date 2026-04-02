package gr.routify.identity.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * Core application configuration for routify-identity-service.
 *
 * <p>Provides ObjectMapper and KafkaTemplate beans needed by services
 * and the Kafka command consumer.
 */
@Configuration
public class IdentityAppConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public ProducerFactory<String, String> identityProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,     StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,   StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,                     "all",
                ProducerConfig.RETRIES_CONFIG,                  3
        ));
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(identityProducerFactory());
    }
}

