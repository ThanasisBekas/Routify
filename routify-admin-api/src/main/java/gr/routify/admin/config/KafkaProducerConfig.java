package gr.routify.admin.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Kafka producer configuration for routify-admin-api.
 *
 * <p>Admin-api publishes command events to Kafka for write operations:
 * route commands, filter commands, user commands, tenant commands.
 * These are consumed by the owning microservice which executes the mutation
 * and publishes the resulting domain event.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> adminProducerFactory() {
        var factory = new DefaultKafkaProducerFactory<String, Object>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,     StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,   JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG,                     "all",
                ProducerConfig.RETRIES_CONFIG,                  3,
                ProducerConfig.LINGER_MS_CONFIG,                5,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,       true,
                // Do NOT add __TypeId__ headers — consumers use @JsonTypeInfo / @JsonSubTypes
                // on DomainEvent to resolve the concrete type from the "type" field in the JSON body.
                JsonSerializer.ADD_TYPE_INFO_HEADERS,           false
        ));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        var template = new KafkaTemplate<>(adminProducerFactory());
        template.setObservationEnabled(true);
        return template;
    }
}

