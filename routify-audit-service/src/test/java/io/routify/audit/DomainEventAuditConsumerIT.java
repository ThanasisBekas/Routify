package io.routify.audit;

import io.routify.audit.domain.AuditLogEntry;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link io.routify.audit.consumer.DomainEventAuditConsumer}.
 *
 * <p>Validates that domain events published to Kafka are consumed and persisted
 * to the {@code audit_log} table. Uses real PostgreSQL + Kafka + RabbitMQ containers
 * via Testcontainers.
 *
 * <p>Convention: {@code *IT.java} suffix — run by maven-failsafe-plugin with
 * {@code mvn verify -DskipITs=false}.
 */
class DomainEventAuditConsumerIT extends AuditServiceIntegrationBase {

    @Test
    @DisplayName("RouteCreated event is persisted to audit_log with correct fields")
    void routeCreatedEvent_isPersisted() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Instant now  = Instant.now();

        DomainEvent event = new DomainEvent.RouteCreated(
                eventId, TENANT_ID, routeId, "test-route", "/api/test", "GET",
                now, "corr-123", ACTOR);

        String json = objectMapper.writeValueAsString(event);
        sendMessage(KafkaTopics.ROUTE_EVENTS, TENANT_ID.toString(), json);

        // Wait for the consumer to process the event
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var entries = auditLogRepository.findAll();
                    assertThat(entries).isNotEmpty();
                    AuditLogEntry entry = entries.getFirst();
                    assertThat(entry.getEventType()).isEqualTo("ROUTE_CREATED");
                    assertThat(entry.getAggregateType()).isEqualTo("ROUTE");
                    assertThat(entry.getAggregateId()).isEqualTo(routeId.toString());
                    assertThat(entry.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(entry.getCorrelationId()).isEqualTo("corr-123");
                    assertThat(entry.getActorId()).isEqualTo(ACTOR);
                    assertThat(entry.getPayload()).contains("test-route");
                });
    }

    @Test
    @DisplayName("FilterCreated event is persisted with FILTER aggregate type")
    void filterCreatedEvent_isPersisted() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID filterId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEvent event = new DomainEvent.FilterCreated(
                eventId, TENANT_ID, filterId, "auth-filter", "AUTH_JWT",
                now, "corr-456", ACTOR);

        String json = objectMapper.writeValueAsString(event);
        sendMessage(KafkaTopics.FILTER_EVENTS, TENANT_ID.toString(), json);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var entries = auditLogRepository.findAll();
                    assertThat(entries).isNotEmpty();
                    AuditLogEntry entry = entries.stream()
                            .filter(e -> "FILTER_CREATED".equals(e.getEventType()))
                            .findFirst()
                            .orElse(null);
                    assertThat(entry).isNotNull();
                    assertThat(entry.getAggregateType()).isEqualTo("FILTER");
                    assertThat(entry.getAggregateId()).isEqualTo(filterId.toString());
                });
    }

    @Test
    @DisplayName("Multiple events from different topics are all persisted")
    void multipleEvents_fromDifferentTopics_allPersisted() throws Exception {
        UUID routeEventId  = UUID.randomUUID();
        UUID tenantEventId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Instant now  = Instant.now();

        DomainEvent routeEvent = new DomainEvent.RouteActivated(
                routeEventId, TENANT_ID, routeId, "my-route", "/api/test", "GET",
                "http://upstream:8080",
                now, "corr-route", ACTOR);
        DomainEvent tenantEvent = new DomainEvent.TenantCreated(
                tenantEventId, TENANT_ID, "acme-corp", "acme", "STARTER",
                now, "corr-tenant", ACTOR);

        sendMessage(KafkaTopics.ROUTE_EVENTS, TENANT_ID.toString(),
                objectMapper.writeValueAsString(routeEvent));
        sendMessage(KafkaTopics.TENANT_EVENTS, TENANT_ID.toString(),
                objectMapper.writeValueAsString(tenantEvent));

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var entries = auditLogRepository.findAll();
                    assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
                    var types = entries.stream().map(AuditLogEntry::getEventType).toList();
                    assertThat(types).contains("ROUTE_ACTIVATED", "TENANT_CREATED");
                });
    }

    @Test
    @DisplayName("CertificateUploaded event is persisted with CERTIFICATE aggregate type")
    void certUploadedEvent_isPersisted() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID certId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEvent event = new DomainEvent.CertificateUploaded(
                eventId, TENANT_ID, certId, "cert-" + certId, "my-cert",
                "ACTIVE", groupId, "group-1", "primary", "group-1",
                now, "corr-cert", ACTOR);

        sendMessage(KafkaTopics.CERT_EVENTS, TENANT_ID.toString(),
                objectMapper.writeValueAsString(event));

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var entries = auditLogRepository.findAll();
                    assertThat(entries).isNotEmpty();
                    AuditLogEntry entry = entries.stream()
                            .filter(e -> "CERTIFICATE_UPLOADED".equals(e.getEventType()))
                            .findFirst()
                            .orElse(null);
                    assertThat(entry).isNotNull();
                    assertThat(entry.getAggregateType()).isEqualTo("CERTIFICATE");
                    assertThat(entry.getAggregateId()).isEqualTo(certId.toString());
                });
    }
}

