package gr.routify.route.config;

import gr.routify.common.kafka.KafkaDlqErrorHandlerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * Kafka configuration for routify-route-service.
 *
 * <p>Provides:
 * <ul>
 *   <li>Producer: for the Outbox poller to publish domain events</li>
 *   <li>Consumer: for command events from routify-admin-api
 *       ({@code routify.route.commands}, {@code routify.filter.commands})</li>
 * </ul>
 * Failed records go to {@code <topic>.DLQ} after exponential back-off.
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ─── Producer ─────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        var factory = new DefaultKafkaProducerFactory<String, Object>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,          bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,       StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,     JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG,                       "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,         "true",
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5",
                ProducerConfig.RETRIES_CONFIG,                    "3",
                // Do NOT add __TypeId__ headers — consumers use @JsonTypeInfo / @JsonSubTypes
                // on DomainEvent to resolve the concrete type from the "type" field in the JSON body.
                // The __TypeId__ header causes StringJsonMessageConverter to attempt direct class
                // loading of the inner record type (e.g. DomainEvent$RouteCreated), which
                // bypasses @JsonSubTypes and fails with a ListenerExecutionFailedException.
                JsonSerializer.ADD_TYPE_INFO_HEADERS,             false
        ));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        var template = new KafkaTemplate<>(producerFactory());
        template.setObservationEnabled(true);
        return template;
    }

    // ─── Consumer (command topics from admin-api) ─────────────────────────────

    @Bean
    public ConsumerFactory<String, Object> routeCommandConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,          bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,     StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,          "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,         "false"
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> routeCommandKafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(routeCommandConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setRecordMessageConverter(new StringJsonMessageConverter(kafkaObjectMapper()));
        factory.setConcurrency(2);
        // C5: Dead-Letter Queue — failed records go to <topic>.DLQ after 30s back-off
        factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    private ObjectMapper kafkaObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
