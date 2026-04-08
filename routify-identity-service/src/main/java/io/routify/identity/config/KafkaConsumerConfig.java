package io.routify.identity.config;

import io.routify.common.kafka.KafkaDlqErrorHandlerFactory;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

import java.util.Map;

/**
 * Kafka consumer + DLQ producer configuration for routify-identity-service.
 *
 * <p>Extracted from {@code UserCommandKafkaConsumer} to avoid a circular
 * bean dependency. Failed records go to {@code <topic>.DLQ} after
 * exponential back-off.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final MeterRegistry meterRegistry;

    public KafkaConsumerConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ─── DLQ Producer ─────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, Object> identityDlqProducerFactory() {
        var factory = new DefaultKafkaProducerFactory<String, Object>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,    bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,                 "1",
                ProducerConfig.RETRIES_CONFIG,              3
        ));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> identityDlqKafkaTemplate() {
        var template = new KafkaTemplate<>(identityDlqProducerFactory());
        template.setObservationEnabled(true);
        return template;
    }

    // ─── Consumer ─────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, Object> userCommandConsumerFactory() {
        var factory = new DefaultKafkaConsumerFactory<String, Object>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       "false"
        ));
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> userCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> userCommandConsumerFactory,
            KafkaTemplate<String, Object> identityDlqKafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(userCommandConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setRecordMessageConverter(new StringJacksonJsonMessageConverter());
        factory.setConcurrency(2);
        // C5: Dead-Letter Queue — failed records go to <topic>.DLQ after 30s back-off
        factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(identityDlqKafkaTemplate));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    /**
     * Listener container factory for webhook event consumers.
     * Consumes domain events from ROUTE_EVENTS, FILTER_EVENTS, CERT_EVENTS, AI_FILTER_DECISIONS
     * and dispatches matching webhook notifications.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> webhookKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> userCommandConsumerFactory,
            KafkaTemplate<String, Object> identityDlqKafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(userCommandConsumerFactory);
        factory.setRecordMessageConverter(new StringJacksonJsonMessageConverter());
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(identityDlqKafkaTemplate));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
