package io.routify.audit;

import io.routify.audit.domain.DlqEvent;
import io.routify.common.event.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link io.routify.audit.consumer.DlqEventConsumer}.
 *
 * <p>Validates that records published to DLQ topics are consumed and persisted
 * to the {@code dlq_event} table. The DLQ consumer receives raw strings (not typed
 * domain events) because DLQ records may have failed precisely due to deserialization errors.
 */
class DlqEventConsumerIT extends AuditServiceIntegrationBase {

    @Test
    @DisplayName("DLQ record is persisted with correct source topic extraction")
    void dlqRecord_isPersisted() throws Exception {
        String dlqTopic = KafkaTopics.DLQ_ROUTE_EVENTS;
        String payload  = "{\"bad\": \"data\"}";

        // Publish directly to the DLQ topic (simulating a dead-lettered record)
        sendMessage(dlqTopic, TENANT_ID.toString(), payload);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var events = dlqEventRepository.findAll();
                    assertThat(events).isNotEmpty();
                    DlqEvent event = events.getFirst();
                    // Source topic should strip the .DLQ suffix
                    assertThat(event.getSourceTopic()).isEqualTo(KafkaTopics.ROUTE_EVENTS);
                    assertThat(event.getDlqTopic()).isEqualTo(dlqTopic);
                    assertThat(event.getRawPayload()).isEqualTo(payload);
                    assertThat(event.getRecordKey()).isEqualTo(TENANT_ID.toString());
                });
    }

    @Test
    @DisplayName("Multiple DLQ records from different topics are all persisted")
    void multipleDlqRecords_allPersisted() throws Exception {
        sendMessage(KafkaTopics.DLQ_ROUTE_EVENTS, "key-1", "route-failure");
        sendMessage(KafkaTopics.DLQ_FILTER_EVENTS, "key-2", "filter-failure");
        sendMessage(KafkaTopics.DLQ_TENANT_EVENTS, "key-3", "tenant-failure");

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var events = dlqEventRepository.findAll();
                    assertThat(events).hasSizeGreaterThanOrEqualTo(3);
                    var sourceTopics = events.stream().map(DlqEvent::getSourceTopic).toList();
                    assertThat(sourceTopics).contains(
                            KafkaTopics.ROUTE_EVENTS,
                            KafkaTopics.FILTER_EVENTS,
                            KafkaTopics.TENANT_EVENTS
                    );
                });
    }
}

