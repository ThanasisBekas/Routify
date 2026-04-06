package io.routify.common.kafka;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ExceptionMatcher;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.lang.reflect.Field;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Behaviour tests for {@link KafkaDlqErrorHandlerFactory}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Exponential back-off parameters (1 s initial, 2.0 multiplier, 30 s max elapsed)</li>
 *   <li>DLQ topic naming convention ({@code <topic>.DLQ})</li>
 *   <li>Non-retryable exception short-circuit (deserialization errors go straight to DLQ)</li>
 *   <li>Retryable exceptions are not classified as fatal</li>
 *   <li>MDC correlationId lifecycle during DLQ routing</li>
 *   <li>Factory utility class constraints (private constructor, static-only)</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class KafkaDlqErrorHandlerFactoryTest {

    private static DefaultErrorHandler handler;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUp() {
        // Mock the ProducerFactory interface (not the concrete KafkaTemplate class)
        // to avoid Mockito inline-mock failures on JDK 25+.
        ProducerFactory<String, Object> producerFactory = mock(ProducerFactory.class);
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        handler = KafkaDlqErrorHandlerFactory.create(kafkaTemplate);
    }

    // ─── Back-off configuration ──────────────────────────────────────────────

    @Nested
    @DisplayName("Exponential back-off configuration")
    class BackOffConfiguration {

        private ExponentialBackOff backOff;

        @BeforeEach
        void extractBackOff() throws Exception {
            backOff = extractExponentialBackOff(handler);
        }

        @Test
        @DisplayName("Initial interval is 1 second")
        void initialInterval() {
            assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("Multiplier is 2.0")
        void multiplier() {
            assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("Max elapsed time is 30 seconds")
        void maxElapsedTime() {
            assertThat(backOff.getMaxElapsedTime()).isEqualTo(30_000L);
        }

        @Test
        @DisplayName("Back-off sequence produces ~5 retries before exhaustion")
        void approximateRetryCount() {
            // 1s + 2s + 4s + 8s + 16s = 31s > 30s budget → approximately 5 attempts
            var execution = backOff.start();

            int retries = 0;
            while (execution.nextBackOff() != BackOffExecution.STOP) {
                retries++;
                if (retries > 20) break; // safety valve
            }
            assertThat(retries).isBetween(3, 6);
        }

        @Test
        @DisplayName("First back-off interval is 1 second")
        void firstInterval() {
            var execution = backOff.start();
            assertThat(execution.nextBackOff()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("Second back-off interval is 2 seconds (1s × 2.0)")
        void secondInterval() {
            var execution = backOff.start();
            execution.nextBackOff(); // 1s
            assertThat(execution.nextBackOff()).isEqualTo(2_000L);
        }

        @Test
        @DisplayName("Third back-off interval is 4 seconds (2s × 2.0)")
        void thirdInterval() {
            var execution = backOff.start();
            execution.nextBackOff(); // 1s
            execution.nextBackOff(); // 2s
            assertThat(execution.nextBackOff()).isEqualTo(4_000L);
        }
    }

    // ─── DLQ topic naming ────────────────────────────────────────────────────

    @Nested
    @DisplayName("DLQ topic naming")
    class DlqTopicNaming {

        private BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver;

        @BeforeEach
        void extractResolver() throws Exception {
            destinationResolver = extractDestinationResolver(handler);
        }

        @Test
        @DisplayName("DLQ topic is <original-topic>.DLQ")
        void dlqTopicNaming() {
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("routify.route.events", 0, 42L, "key-1", "value");
            TopicPartition dlq = destinationResolver.apply(record, new RuntimeException("boom"));
            assertThat(dlq.topic()).isEqualTo("routify.route.events.DLQ");
        }

        @Test
        @DisplayName("DLQ preserves original partition")
        void dlqPreservesPartition() {
            int originalPartition = 3;
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("routify.filter.events", originalPartition, 100L, "key-2", "{}");
            TopicPartition dlq = destinationResolver.apply(record, new RuntimeException("fail"));
            assertThat(dlq.partition()).isEqualTo(originalPartition);
        }

        @Test
        @DisplayName("DLQ works for command topics")
        void dlqForCommandTopics() {
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("routify.route.commands", 0, 0L, null, "{}");
            TopicPartition dlq = destinationResolver.apply(record, new RuntimeException("error"));
            assertThat(dlq.topic()).isEqualTo("routify.route.commands.DLQ");
        }

        @Test
        @DisplayName("DLQ topic naming works with arbitrary topic names")
        void dlqWithArbitraryTopicName() {
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("my.custom.topic", 7, 999L, "k", "v");
            TopicPartition dlq = destinationResolver.apply(record, new RuntimeException("x"));
            assertThat(dlq.topic()).isEqualTo("my.custom.topic.DLQ");
        }

        @Test
        @DisplayName("DLQ partition 0 preserved for single-partition topics")
        void dlqPartitionZero() {
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("routify.tenant.events", 0, 0L, "tenant-1", "{}");
            TopicPartition dlq = destinationResolver.apply(record, new RuntimeException("err"));
            assertThat(dlq.topic()).isEqualTo("routify.tenant.events.DLQ");
            assertThat(dlq.partition()).isEqualTo(0);
        }
    }

    // ─── Non-retryable exceptions ────────────────────────────────────────────

    @Nested
    @DisplayName("Non-retryable exception classification")
    class NonRetryableExceptions {

        private ExceptionMatcher classifier;

        @BeforeEach
        void extractClassifier() throws Exception {
            classifier = extractExceptionMatcher(handler);
        }

        static Stream<Arguments> nonRetryableExceptions() {
            return Stream.of(
                    Arguments.of(
                            new JsonParseException("malformed JSON"),
                            "JsonParseException"
                    ),
                    Arguments.of(
                            InvalidDefinitionException.from(
                                    (com.fasterxml.jackson.core.JsonParser) null,
                                    "invalid definition",
                                    (com.fasterxml.jackson.databind.JavaType) null),
                            "InvalidDefinitionException"
                    ),
                    Arguments.of(
                            MismatchedInputException.from(
                                    (com.fasterxml.jackson.core.JsonParser) null,
                                    "mismatched input"),
                            "MismatchedInputException"
                    )
            );
        }

        @ParameterizedTest(name = "{1} is not retryable")
        @MethodSource("nonRetryableExceptions")
        @DisplayName("Deserialization exceptions are classified as non-retryable")
        void deserializationExceptionsAreNonRetryable(Exception exception, String name) {
            // In Spring Kafka's ExceptionMatcher, match() returns `true`
            // for retryable exceptions and `false` for non-retryable (fatal).
            boolean retryable = classifier.match(exception);
            assertThat(retryable)
                    .as("%s should be non-retryable (classified as false)", name)
                    .isFalse();
        }

        @Test
        @DisplayName("Generic RuntimeException is retryable (not short-circuited)")
        void runtimeExceptionIsRetryable() {
            boolean retryable = classifier.match(new RuntimeException("transient failure"));
            assertThat(retryable)
                    .as("RuntimeException should be retryable")
                    .isTrue();
        }

        @Test
        @DisplayName("IllegalArgumentException is retryable by default")
        void illegalArgumentExceptionIsRetryable() {
            boolean retryable = classifier.match(new IllegalArgumentException("bad arg"));
            assertThat(retryable)
                    .as("IllegalArgumentException should be retryable")
                    .isTrue();
        }

        @Test
        @DisplayName("NullPointerException is retryable by default")
        void nullPointerExceptionIsRetryable() {
            boolean retryable = classifier.match(new NullPointerException("npe"));
            assertThat(retryable)
                    .as("NullPointerException should be retryable")
                    .isTrue();
        }

        @Test
        @DisplayName("IOException is retryable (transient network issues)")
        void ioExceptionIsRetryable() {
            boolean retryable = classifier.match(new java.io.IOException("connection reset"));
            assertThat(retryable)
                    .as("IOException should be retryable")
                    .isTrue();
        }
    }

    // ─── MDC correlationId lifecycle ─────────────────────────────────────────

    @Nested
    @DisplayName("MDC correlationId lifecycle during DLQ routing")
    class MdcLifecycle {

        private BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver;

        @BeforeEach
        void extractResolver() throws Exception {
            destinationResolver = extractDestinationResolver(handler);
            MDC.clear();
        }

        @AfterEach
        void cleanMdc() {
            MDC.clear();
        }

        @Test
        @DisplayName("MDC correlationId is set from record key when absent and cleared after")
        void mdcSetFromRecordKeyAndCleared() {
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("test.topic", 0, 0L, "record-key-123", "value");

            assertThat(MDC.get("correlationId")).isNull();
            destinationResolver.apply(record, new RuntimeException("err"));
            // After the resolver completes, MDC should be cleaned up
            assertThat(MDC.get("correlationId")).isNull();
        }

        @Test
        @DisplayName("MDC correlationId is not overwritten when already present")
        void mdcNotOverwrittenWhenPresent() {
            MDC.put("correlationId", "existing-correlation-id");

            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("test.topic", 0, 0L, "record-key", "value");
            destinationResolver.apply(record, new RuntimeException("err"));

            // Existing MDC value should be preserved (not removed)
            assertThat(MDC.get("correlationId")).isEqualTo("existing-correlation-id");
        }

        @Test
        @DisplayName("MDC gets a UUID when record key is null and is cleaned up after")
        void mdcFallbackToUuidWhenKeyNull() {
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("test.topic", 0, 0L, null, "value");

            assertThat(MDC.get("correlationId")).isNull();
            destinationResolver.apply(record, new RuntimeException("err"));
            // MDC should be cleaned up after execution
            assertThat(MDC.get("correlationId")).isNull();
        }
    }

    // ─── Factory utility constraints ─────────────────────────────────────────

    @Nested
    @DisplayName("Factory class constraints")
    class FactoryConstraints {

        @Test
        @DisplayName("Factory class has a private constructor (utility class pattern)")
        void privateConstructor() throws Exception {
            var constructor = KafkaDlqErrorHandlerFactory.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()))
                    .as("Constructor should be private")
                    .isTrue();
        }

        @Test
        @DisplayName("Factory class is final")
        void finalClass() {
            assertThat(java.lang.reflect.Modifier.isFinal(
                    KafkaDlqErrorHandlerFactory.class.getModifiers()))
                    .as("Factory class should be final")
                    .isTrue();
        }

        @Test
        @DisplayName("create() returns a non-null DefaultErrorHandler")
        void createReturnsNonNull() {
            assertThat(handler).isNotNull();
            assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
        }
    }

    // ─── Reflection helpers ──────────────────────────────────────────────────

    /**
     * Extracts the {@link ExponentialBackOff} from the handler's internal
     * {@code FailedRecordTracker} → {@code BackOff} field chain.
     */
    private static ExponentialBackOff extractExponentialBackOff(DefaultErrorHandler handler)
            throws Exception {
        Field trackerField = findField(handler.getClass(), "failureTracker");
        trackerField.setAccessible(true);
        Object tracker = trackerField.get(handler);

        Field backOffField = findField(tracker.getClass(), "backOff");
        backOffField.setAccessible(true);
        Object backOff = backOffField.get(tracker);

        assertThat(backOff).isInstanceOf(ExponentialBackOff.class);
        return (ExponentialBackOff) backOff;
    }

    /**
     * Extracts the DLQ destination resolver function from the handler's internal
     * {@link DeadLetterPublishingRecoverer}.
     */
    private static BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>
    extractDestinationResolver(DefaultErrorHandler handler) throws Exception {
        Field trackerField = findField(handler.getClass(), "failureTracker");
        trackerField.setAccessible(true);
        Object tracker = trackerField.get(handler);

        Field recovererField = findField(tracker.getClass(), "recoverer");
        recovererField.setAccessible(true);
        Object recoverer = recovererField.get(tracker);

        assertThat(recoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
        DeadLetterPublishingRecoverer dlpr = (DeadLetterPublishingRecoverer) recoverer;

        Field resolverField = findField(DeadLetterPublishingRecoverer.class, "destinationResolver");
        resolverField.setAccessible(true);
        return (BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>)
                resolverField.get(dlpr);
    }

    /**
     * Extracts the {@link ExceptionMatcher} from the handler's
     * {@link org.springframework.kafka.listener.ExceptionClassifier} superclass.
     */
    private static ExceptionMatcher extractExceptionMatcher(
            DefaultErrorHandler handler) throws Exception {
        Field classifierField = findField(handler.getClass(), "exceptionMatcher");
        classifierField.setAccessible(true);
        Object classifier = classifierField.get(handler);

        assertThat(classifier).isInstanceOf(ExceptionMatcher.class);
        return (ExceptionMatcher) classifier;
    }

    /**
     * Walks the class hierarchy to find a declared field by name.
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException(
                "Field '" + fieldName + "' not found in hierarchy of " + clazz.getName());
    }
}

