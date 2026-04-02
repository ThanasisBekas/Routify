package gr.routify.admin.config;

import gr.routify.common.kafka.KafkaDlqErrorHandlerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

/**
 * Kafka consumer configuration for routify-admin-api.
 *
 * <p>Provides the {@code kafkaListenerContainerFactory} bean required by
 * {@link gr.routify.admin.sse.DashboardEventBroadcaster} and
 * {@link gr.routify.admin.ws.WebSocketEventBroadcaster} for consuming domain
 * events (route, filter, gateway, tenant, user, certificate, cert-group, audit)
 * and broadcasting them to SSE/WebSocket clients.
 *
 * <p>Failed records go to {@code <topic>.DLQ} after exponential back-off.
 * The {@link KafkaTemplate} is supplied by {@link KafkaProducerConfig}.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:routify-admin-api}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> adminConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,                 groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "latest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "false"
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            KafkaTemplate<String, String> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(adminConsumerFactory());
        factory.setConcurrency(2);
        // C5: Dead-Letter Queue — failed records go to <topic>.DLQ after 30s back-off
        factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate));
        return factory;
    }
}

