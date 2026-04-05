package gr.routify.route;

import com.fasterxml.jackson.databind.JsonNode;
import gr.routify.common.domain.FilterType;
import gr.routify.common.domain.RouteStatus;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.route.domain.OutboxEvent;
import gr.routify.route.domain.Route;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the routify-route-service Kafka command pipeline.
 *
 * <p>Tests the full flow: Kafka command → service layer → DB persistence → Outbox write
 * → OutboxPoller publishes DomainEvent to Kafka. Uses real PostgreSQL + Kafka containers.
 *
 * <h3>Test naming convention</h3>
 * Uses {@code *IT} suffix so Maven Failsafe picks these up during {@code mvn verify}.
 */
class RouteCommandIntegrationIT extends RouteServiceIntegrationBase {

    // ─── Test 1: CreateRoute command → Route persisted + OutboxEvent written ──

    @Test
    @DisplayName("CreateRoute command persists route in DRAFT status and writes outbox event")
    void createRoute_persistsRouteAndOutboxEvent() throws Exception {
        UUID commandId = UUID.randomUUID();
        var cmd = new CommandEvent.CreateRoute(
                commandId, TENANT_ID, ACTOR, Instant.now(),
                "test-route", "A test route",
                "/api/v1/test/**", "GET,POST",
                "http://upstream:8080", "/api/v1",
                Map.of("timeout", 30000)
        );

        sendCommand(KafkaTopics.ROUTE_COMMANDS, cmd);

        // Wait for the Kafka consumer to process the command and persist the route
        await().atMost(15, SECONDS).untilAsserted(() -> {
            List<Route> routes = routeRepository.findAllByTenantId(TENANT_ID,
                    org.springframework.data.domain.Pageable.unpaged()).getContent();
            assertThat(routes).hasSize(1);

            Route route = routes.get(0);
            assertThat(route.getName()).isEqualTo("test-route");
            assertThat(route.getDescription()).isEqualTo("A test route");
            assertThat(route.getPathPattern()).isEqualTo("/api/v1/test/**");
            assertThat(route.getMethods()).isEqualTo("GET,POST");
            assertThat(route.getUpstreamUri()).isEqualTo("http://upstream:8080");
            assertThat(route.getStripPrefix()).isEqualTo("/api/v1");
            assertThat(route.getStatus()).isEqualTo(RouteStatus.DRAFT);
            assertThat(route.getVersion()).isEqualTo(1);
        });

        // Verify outbox event was written in the same transaction
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outbox = outboxEvents.get(0);
            assertThat(outbox.getTopic()).isEqualTo(KafkaTopics.ROUTE_EVENTS);
            assertThat(outbox.getEventType()).isEqualTo("RouteCreated");
            assertThat(outbox.getAggregateType()).isEqualTo("Route");
            assertThat(outbox.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        });

        // Verify idempotency ledger was updated
        assertThat(processedCommandRepository.existsById(commandId)).isTrue();
    }

    // ─── Test 2: OutboxPoller publishes RouteCreated DomainEvent to Kafka ─────

    @Test
    @DisplayName("OutboxPoller publishes RouteCreated event to Kafka and marks outbox PUBLISHED")
    void outboxPoller_publishesRouteCreatedToKafka() throws Exception {
        UUID commandId = UUID.randomUUID();
        var cmd = new CommandEvent.CreateRoute(
                commandId, TENANT_ID, ACTOR, Instant.now(),
                "outbox-test-route", null,
                "/api/v1/outbox/**", "*",
                "http://upstream:8080", null, null
        );

        sendCommand(KafkaTopics.ROUTE_COMMANDS, cmd);

        // Wait for route to be persisted
        await().atMost(15, SECONDS).until(() -> routeRepository.countByTenantId(TENANT_ID) == 1);

        // Manually trigger the outbox poller (deterministic — no @Scheduled)
        outboxPoller.pollAndPublish();

        // Verify outbox entry is now PUBLISHED
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).allMatch(e -> e.getStatus() == OutboxEvent.Status.PUBLISHED);
        });

        // Verify the DomainEvent appeared on the Kafka ROUTE_EVENTS topic
        List<ConsumerRecord<String, String>> records = drainTopic(KafkaTopics.ROUTE_EVENTS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        // Parse and verify the event payload
        JsonNode eventJson = objectMapper.readTree(records.get(0).value());
        assertThat(eventJson.get("type").asText()).isEqualTo("ROUTE_CREATED");
        assertThat(eventJson.has("routeId")).isTrue();
        assertThat(eventJson.get("tenantId").asText()).isEqualTo(TENANT_ID.toString());
    }

    // ─── Test 3: ActivateRoute → ACTIVE + RouteActivated + GatewayReload ─────

    @Test
    @DisplayName("ActivateRoute changes status to ACTIVE and produces RouteActivated + GatewayReloadRequested")
    void activateRoute_changesStatusAndPublishesTwoEvents() throws Exception {
        // 1. Create a DRAFT route
        UUID createCmdId = UUID.randomUUID();
        var createCmd = new CommandEvent.CreateRoute(
                createCmdId, TENANT_ID, ACTOR, Instant.now(),
                "activate-test", null,
                "/api/v1/activate/**", "GET",
                "http://upstream:8080", null, null
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, createCmd);

        await().atMost(15, SECONDS).until(() -> routeRepository.countByTenantId(TENANT_ID) == 1);
        Route draft = routeRepository.findAllByTenantId(TENANT_ID,
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);
        assertThat(draft.getStatus()).isEqualTo(RouteStatus.DRAFT);

        // Flush the CreateRoute outbox event first
        outboxPoller.pollAndPublish();

        // 2. Send ActivateRoute command
        UUID activateCmdId = UUID.randomUUID();
        var activateCmd = new CommandEvent.ActivateRoute(
                activateCmdId, TENANT_ID, ACTOR, Instant.now(), draft.getId()
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, activateCmd);

        // Wait for status to become ACTIVE
        await().atMost(15, SECONDS).untilAsserted(() -> {
            Route route = routeRepository.findByIdAndTenantId(draft.getId(), TENANT_ID).orElseThrow();
            assertThat(route.getStatus()).isEqualTo(RouteStatus.ACTIVE);
            assertThat(route.getVersion()).isEqualTo(2); // incremented on activation
        });

        // Trigger outbox poller for the activation events
        outboxPoller.pollAndPublish();

        // Verify RouteActivated event on ROUTE_EVENTS topic
        List<ConsumerRecord<String, String>> routeEvents = drainTopic(KafkaTopics.ROUTE_EVENTS, Duration.ofSeconds(10));
        boolean hasActivated = routeEvents.stream().anyMatch(r -> {
            try {
                return objectMapper.readTree(r.value()).get("type").asText().equals("ROUTE_ACTIVATED");
            } catch (Exception e) { return false; }
        });
        assertThat(hasActivated).as("Expected ROUTE_ACTIVATED event on %s", KafkaTopics.ROUTE_EVENTS).isTrue();

        // Verify GatewayReloadRequested event on GATEWAY_RELOAD topic
        List<ConsumerRecord<String, String>> reloadEvents = drainTopic(KafkaTopics.GATEWAY_RELOAD, Duration.ofSeconds(10));
        boolean hasReload = reloadEvents.stream().anyMatch(r -> {
            try {
                return objectMapper.readTree(r.value()).get("type").asText().equals("GATEWAY_RELOAD_REQUESTED");
            } catch (Exception e) { return false; }
        });
        assertThat(hasReload).as("Expected GATEWAY_RELOAD_REQUESTED event on %s", KafkaTopics.GATEWAY_RELOAD).isTrue();
    }

    // ─── Test 4: UpdateRoute → fields updated + RouteUpdated event ────────────

    @Test
    @DisplayName("UpdateRoute command updates route fields and publishes RouteUpdated event")
    void updateRoute_updatesFieldsAndPublishesRouteUpdated() throws Exception {
        // Create a DRAFT route first
        UUID createCmdId = UUID.randomUUID();
        var createCmd = new CommandEvent.CreateRoute(
                createCmdId, TENANT_ID, ACTOR, Instant.now(),
                "update-me", "original desc",
                "/api/v1/update/**", "GET",
                "http://upstream:8080", null, null
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, createCmd);

        await().atMost(15, SECONDS).until(() -> routeRepository.countByTenantId(TENANT_ID) == 1);
        Route original = routeRepository.findAllByTenantId(TENANT_ID,
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);

        // Flush create outbox event
        outboxPoller.pollAndPublish();

        // Send UpdateRoute command
        UUID updateCmdId = UUID.randomUUID();
        var updateCmd = new CommandEvent.UpdateRoute(
                updateCmdId, TENANT_ID, ACTOR, Instant.now(),
                original.getId(),
                "updated-name", "updated description",
                null, null, null, null, null
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, updateCmd);

        // Wait for the update to be persisted
        await().atMost(15, SECONDS).untilAsserted(() -> {
            Route route = routeRepository.findByIdAndTenantId(original.getId(), TENANT_ID).orElseThrow();
            assertThat(route.getName()).isEqualTo("updated-name");
            assertThat(route.getDescription()).isEqualTo("updated description");
        });

        // Trigger outbox poller
        outboxPoller.pollAndPublish();

        // Verify RouteUpdated event
        List<ConsumerRecord<String, String>> events = drainTopic(KafkaTopics.ROUTE_EVENTS, Duration.ofSeconds(10));
        boolean hasUpdated = events.stream().anyMatch(r -> {
            try {
                return objectMapper.readTree(r.value()).get("type").asText().equals("ROUTE_UPDATED");
            } catch (Exception e) { return false; }
        });
        assertThat(hasUpdated).as("Expected ROUTE_UPDATED event").isTrue();
    }

    // ─── Test 5: DeleteRoute (non-active) → ARCHIVED + RouteDeleted event ────

    @Test
    @DisplayName("DeleteRoute archives a DRAFT route and publishes RouteDeleted event")
    void deleteRoute_archivesNonActiveRouteAndPublishesRouteDeleted() throws Exception {
        // Create a DRAFT route
        UUID createCmdId = UUID.randomUUID();
        var createCmd = new CommandEvent.CreateRoute(
                createCmdId, TENANT_ID, ACTOR, Instant.now(),
                "delete-me", null,
                "/api/v1/delete/**", "*",
                "http://upstream:8080", null, null
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, createCmd);

        await().atMost(15, SECONDS).until(() -> routeRepository.countByTenantId(TENANT_ID) == 1);
        Route route = routeRepository.findAllByTenantId(TENANT_ID,
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);

        // Flush create outbox event
        outboxPoller.pollAndPublish();

        // Send DeleteRoute command
        UUID deleteCmdId = UUID.randomUUID();
        var deleteCmd = new CommandEvent.DeleteRoute(
                deleteCmdId, TENANT_ID, ACTOR, Instant.now(), route.getId()
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, deleteCmd);

        // Wait for route to be ARCHIVED
        await().atMost(15, SECONDS).untilAsserted(() -> {
            Route deleted = routeRepository.findById(route.getId()).orElseThrow();
            assertThat(deleted.getStatus()).isEqualTo(RouteStatus.ARCHIVED);
        });

        // Trigger outbox poller
        outboxPoller.pollAndPublish();

        // Verify RouteDeleted event
        List<ConsumerRecord<String, String>> events = drainTopic(KafkaTopics.ROUTE_EVENTS, Duration.ofSeconds(10));
        boolean hasDeleted = events.stream().anyMatch(r -> {
            try {
                return objectMapper.readTree(r.value()).get("type").asText().equals("ROUTE_DELETED");
            } catch (Exception e) { return false; }
        });
        assertThat(hasDeleted).as("Expected ROUTE_DELETED event").isTrue();
    }

    // ─── Test 6: Duplicate command is skipped (idempotency) ──────────────────

    @Test
    @DisplayName("Duplicate command with same commandId is skipped — only one route created")
    void duplicateCommand_isSkippedViaIdempotency() throws Exception {
        UUID commandId = UUID.randomUUID();
        var cmd = new CommandEvent.CreateRoute(
                commandId, TENANT_ID, ACTOR, Instant.now(),
                "idempotent-route", null,
                "/api/v1/idem/**", "*",
                "http://upstream:8080", null, null
        );

        // Send the same command twice
        sendCommand(KafkaTopics.ROUTE_COMMANDS, cmd);
        sendCommand(KafkaTopics.ROUTE_COMMANDS, cmd);

        // Wait for processing — give time for both messages to be consumed
        await().atMost(15, SECONDS).until(() -> routeRepository.countByTenantId(TENANT_ID) >= 1);

        // Small additional wait to ensure the second message has been consumed (and skipped)
        Thread.sleep(2000);

        // Only 1 route should exist
        assertThat(routeRepository.countByTenantId(TENANT_ID)).isEqualTo(1);

        // Only 1 processed command entry
        assertThat(processedCommandRepository.count()).isEqualTo(1);
        assertThat(processedCommandRepository.existsById(commandId)).isTrue();
    }

    // ─── Test 7: CreateFilter → FilterDefinition persisted + FilterCreated ───

    @Test
    @DisplayName("CreateFilter command persists filter and publishes FilterCreated event")
    void createFilter_persistsFilterAndPublishesFilterCreated() throws Exception {
        UUID commandId = UUID.randomUUID();
        var cmd = new CommandEvent.CreateFilter(
                commandId, TENANT_ID, ACTOR, Instant.now(),
                "rate-limit-filter", "Rate limit to 100 req/s",
                FilterType.RATE_LIMIT_FIXED_WINDOW,
                Map.of("maxRequests", 100, "windowSeconds", 60),
                null
        );

        sendCommand(KafkaTopics.FILTER_COMMANDS, cmd);

        // Wait for the filter to be persisted
        await().atMost(15, SECONDS).untilAsserted(() -> {
            var filters = filterDefinitionRepository.findAllByTenantId(TENANT_ID,
                    org.springframework.data.domain.Pageable.unpaged()).getContent();
            assertThat(filters).hasSize(1);

            var filter = filters.get(0);
            assertThat(filter.getName()).isEqualTo("rate-limit-filter");
            assertThat(filter.getFilterType()).isEqualTo(FilterType.RATE_LIMIT_FIXED_WINDOW);
            assertThat(filter.isEnabled()).isTrue();
            assertThat(filter.getUsageCount()).isEqualTo(0);
        });

        // Trigger outbox poller
        outboxPoller.pollAndPublish();

        // Verify FilterCreated event
        List<ConsumerRecord<String, String>> events = drainTopic(KafkaTopics.FILTER_EVENTS, Duration.ofSeconds(10));
        assertThat(events).isNotEmpty();

        JsonNode eventJson = objectMapper.readTree(events.get(0).value());
        assertThat(eventJson.get("type").asText()).isEqualTo("FILTER_CREATED");
        assertThat(eventJson.has("filterId")).isTrue();
        assertThat(eventJson.get("filterType").asText()).isEqualTo("RATE_LIMIT_FIXED_WINDOW");
    }

    // ─── Test 8: Duplicate route name → conflict (no second route created) ───

    @Test
    @DisplayName("CreateRoute with duplicate name causes conflict — only one route persisted")
    void createRouteWithDuplicateName_onlyOneRoutePersisted() throws Exception {
        // First route — should succeed
        UUID cmd1Id = UUID.randomUUID();
        var cmd1 = new CommandEvent.CreateRoute(
                cmd1Id, TENANT_ID, ACTOR, Instant.now(),
                "duplicate-name-route", null,
                "/api/v1/dup1/**", "*",
                "http://upstream:8080", null, null
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, cmd1);

        await().atMost(15, SECONDS).until(() -> routeRepository.countByTenantId(TENANT_ID) == 1);

        // Second route with same name — should fail with Conflict
        UUID cmd2Id = UUID.randomUUID();
        var cmd2 = new CommandEvent.CreateRoute(
                cmd2Id, TENANT_ID, ACTOR, Instant.now(),
                "duplicate-name-route", null,
                "/api/v1/dup2/**", "*",
                "http://upstream-2:8080", null, null
        );
        sendCommand(KafkaTopics.ROUTE_COMMANDS, cmd2);

        // Wait some time for the second command to be processed (and fail)
        Thread.sleep(3000);

        // Only 1 route should exist — the second was rejected
        assertThat(routeRepository.countByTenantId(TENANT_ID)).isEqualTo(1);

        // The first route's name should be preserved
        Route route = routeRepository.findAllByTenantId(TENANT_ID,
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);
        assertThat(route.getName()).isEqualTo("duplicate-name-route");
        assertThat(route.getPathPattern()).isEqualTo("/api/v1/dup1/**");
    }
}

