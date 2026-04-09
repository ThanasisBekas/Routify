package io.routify.identity;

import com.fasterxml.jackson.databind.JsonNode;
import io.routify.common.domain.UserRole;
import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.security.RedisKeys;
import io.routify.identity.domain.AppUser;
import io.routify.identity.domain.IdentityOutboxEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the identity-service Kafka command consumers.
 *
 * <p>Tests the full Kafka command pipeline: command → consumer → service layer →
 * DB persistence → Outbox write → OutboxPoller publishes DomainEvent to Kafka.
 */
class UserCommandKafkaIntegrationIT extends IdentityServiceIntegrationBase {

    // ─── Test 1: CreateUser via Kafka ──────────────────────────────────────────

    @Test
    @DisplayName("CreateUser command persists user in DB and writes outbox event")
    void createUser_viaKafka_persistsUserAndWritesOutbox() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID tenantId = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow().getId();

        var cmd = new CommandEvent.CreateUser(
                commandId, tenantId, "test-admin", Instant.now(),
                "newuser", "newuser@routify.io", "securepass123", UserRole.OPERATOR, null
        );

        sendCommand(KafkaTopics.USER_COMMANDS, cmd);

        // Wait for user to be persisted
        await().atMost(15, SECONDS).untilAsserted(() -> {
            var user = userRepository.findByUsernameAndTenantId("newuser", tenantId);
            assertThat(user).isPresent();
            assertThat(user.get().getEmail()).isEqualTo("newuser@routify.io");
            assertThat(user.get().getRole()).isEqualTo(UserRole.OPERATOR);
            assertThat(user.get().getStatus()).isEqualTo(AppUser.Status.ACTIVE);
        });

        // Verify outbox event was written
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<IdentityOutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).anyMatch(e ->
                    e.getEventType().equals("UserCreated") &&
                    e.getTopic().equals(KafkaTopics.USER_EVENTS) &&
                    e.getStatus() == IdentityOutboxEvent.Status.PENDING);
        });

        // Verify idempotency ledger
        assertThat(processedCommandRepository.existsById(commandId)).isTrue();

        // Cleanup
        var user = userRepository.findByUsernameAndTenantId("newuser", tenantId).orElseThrow();
        userRepository.deleteById(user.getId());
    }

    // ─── Test 2: OutboxPoller publishes UserCreated to Kafka ───────────────────

    @Test
    @DisplayName("OutboxPoller publishes UserCreated event to Kafka and marks outbox PUBLISHED")
    void outboxPoller_publishesUserCreatedToKafka() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID tenantId = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow().getId();

        var cmd = new CommandEvent.CreateUser(
                commandId, tenantId, "test-admin", Instant.now(),
                "outbox-user", "outbox-user@routify.io", "securepass123", UserRole.VIEWER, null
        );

        sendCommand(KafkaTopics.USER_COMMANDS, cmd);

        // Wait for user to be persisted
        await().atMost(15, SECONDS).until(() ->
                userRepository.findByUsernameAndTenantId("outbox-user", tenantId).isPresent());

        // Manually trigger the outbox poller
        outboxPoller.pollAndPublish();

        // Verify outbox is PUBLISHED
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<IdentityOutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).allMatch(e -> e.getStatus() == IdentityOutboxEvent.Status.PUBLISHED);
        });

        // Verify DomainEvent appeared on Kafka USER_EVENTS topic
        List<ConsumerRecord<String, String>> records = drainTopic(KafkaTopics.USER_EVENTS, Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        JsonNode eventJson = objectMapper.readTree(records.get(0).value());
        assertThat(eventJson.get("type").asText()).isEqualTo("USER_CREATED");
        assertThat(eventJson.has("userId")).isTrue();
        assertThat(eventJson.get("tenantId").asText()).isEqualTo(tenantId.toString());

        // Cleanup
        var user = userRepository.findByUsernameAndTenantId("outbox-user", tenantId).orElseThrow();
        userRepository.deleteById(user.getId());
    }

    // ─── Test 3: Duplicate CreateUser command is skipped (idempotency) ─────────

    @Test
    @DisplayName("Duplicate CreateUser command with same commandId is skipped — only one user created")
    void duplicateCreateUser_isSkippedViaIdempotency() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID tenantId = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow().getId();

        var cmd = new CommandEvent.CreateUser(
                commandId, tenantId, "test-admin", Instant.now(),
                "idem-user", "idem-user@routify.io", "securepass123", UserRole.VIEWER, null
        );

        // Send the same command twice
        sendCommand(KafkaTopics.USER_COMMANDS, cmd);
        sendCommand(KafkaTopics.USER_COMMANDS, cmd);

        // Wait for first message to be consumed
        await().atMost(15, SECONDS).until(() ->
                userRepository.findByUsernameAndTenantId("idem-user", tenantId).isPresent());

        // Give time for the second message to be consumed (and skipped)
        Thread.sleep(3000);

        // Only 1 user should exist with this username
        AppUser idemUser = userRepository.findByUsernameAndTenantId("idem-user", tenantId).orElseThrow();
        assertThat(idemUser).isNotNull();

        // Only 1 processed command entry for this commandId
        assertThat(processedCommandRepository.existsById(commandId)).isTrue();

        // Cleanup
        userRepository.deleteById(idemUser.getId());
    }

    // ─── Test 4: UpdateUser via Kafka ──────────────────────────────────────────

    @Test
    @DisplayName("UpdateUser command updates user fields and writes outbox event")
    void updateUser_viaKafka_updatesFieldsAndWritesOutbox() throws Exception {
        UUID tenantId = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow().getId();

        // Create a user first
        UUID createCmdId = UUID.randomUUID();
        var createCmd = new CommandEvent.CreateUser(
                createCmdId, tenantId, "test-admin", Instant.now(),
                "update-me", "update-me@routify.io", "securepass123", UserRole.VIEWER, null
        );
        sendCommand(KafkaTopics.USER_COMMANDS, createCmd);

        await().atMost(15, SECONDS).until(() ->
                userRepository.findByUsernameAndTenantId("update-me", tenantId).isPresent());

        AppUser created = userRepository.findByUsernameAndTenantId("update-me", tenantId).orElseThrow();

        // Send UpdateUser command
        UUID updateCmdId = UUID.randomUUID();
        var updateCmd = new CommandEvent.UpdateUser(
                updateCmdId, tenantId, "test-admin", Instant.now(),
                created.getId(), "updated-name", "updated@routify.io", UserRole.OPERATOR, null
        );
        sendCommand(KafkaTopics.USER_COMMANDS, updateCmd);

        // Wait for the update
        await().atMost(15, SECONDS).untilAsserted(() -> {
            AppUser user = userRepository.findById(created.getId()).orElseThrow();
            assertThat(user.getUsername()).isEqualTo("updated-name");
            assertThat(user.getEmail()).isEqualTo("updated@routify.io");
            assertThat(user.getRole()).isEqualTo(UserRole.OPERATOR);
        });

        // Cleanup
        userRepository.deleteById(created.getId());
    }

    // ─── Test 5: DeleteUser via Kafka ──────────────────────────────────────────

    @Test
    @DisplayName("DeleteUser command soft-deletes user (status=DELETED)")
    void deleteUser_viaKafka_softDeletesUser() throws Exception {
        UUID tenantId = tenantRepository.findBySlug(PLATFORM_SLUG).orElseThrow().getId();

        // Create a user first
        UUID createCmdId = UUID.randomUUID();
        var createCmd = new CommandEvent.CreateUser(
                createCmdId, tenantId, "test-admin", Instant.now(),
                "delete-me", "delete-me@routify.io", "securepass123", UserRole.VIEWER, null
        );
        sendCommand(KafkaTopics.USER_COMMANDS, createCmd);

        await().atMost(15, SECONDS).until(() ->
                userRepository.findByUsernameAndTenantId("delete-me", tenantId).isPresent());

        AppUser created = userRepository.findByUsernameAndTenantId("delete-me", tenantId).orElseThrow();

        // Send DeleteUser command
        UUID deleteCmdId = UUID.randomUUID();
        var deleteCmd = new CommandEvent.DeleteUser(
                deleteCmdId, tenantId, "test-admin", Instant.now(),
                created.getId()
        );
        sendCommand(KafkaTopics.USER_COMMANDS, deleteCmd);

        // Wait for soft-delete
        await().atMost(15, SECONDS).untilAsserted(() -> {
            AppUser user = userRepository.findById(created.getId()).orElseThrow();
            assertThat(user.getStatus()).isEqualTo(AppUser.Status.DELETED);
        });

        // Cleanup
        userRepository.deleteById(created.getId());
    }

    // ─── Test 6: Auth Logout via Kafka — refresh token blocklisted in Redis ───

    @Test
    @DisplayName("Logout command via Kafka blocklists refresh token JTI in Redis")
    void authLogout_viaKafka_blocklistsRefreshTokenInRedis() throws Exception {
        // Login to get a valid refresh token
        String loginJson = """
                {"username": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(ADMIN_USERNAME, ADMIN_PASSWORD, PLATFORM_SLUG);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull().startsWith("refresh_token=");
        String refreshToken = setCookie.split(";")[0].substring("refresh_token=".length());

        // Extract the JTI from the refresh token
        var claims = jwtService.validateAndParseClaims(refreshToken);
        String jti = claims.getId();

        // Ensure Redis does NOT have this JTI yet
        assertThat(redisTemplate.hasKey(RedisKeys.BLOCKLIST_PREFIX + jti)).isFalse();

        // Send Logout command via Kafka
        UUID commandId = UUID.randomUUID();
        var logoutCmd = new CommandEvent.Logout(
                commandId, null, "admin", Instant.now(), refreshToken
        );
        sendCommand(KafkaTopics.AUTH_COMMANDS, logoutCmd);

        // Wait for Redis key to appear
        await().atMost(15, SECONDS).untilAsserted(() ->
                assertThat(redisTemplate.hasKey(RedisKeys.BLOCKLIST_PREFIX + jti)).isTrue());

        // Attempt refresh with the blocklisted token — should fail
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());
    }
}

