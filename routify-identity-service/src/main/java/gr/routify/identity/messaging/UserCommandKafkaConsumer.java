package gr.routify.identity.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.common.domain.UserRole;
import gr.routify.common.event.KafkaTopics;
import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka command consumer for routify-identity-service.
 *
 * <p>Consumes user command events published by routify-admin-api:
 * <ul>
 *   <li>CREATE_USER</li>
 *   <li>UPDATE_USER</li>
 *   <li>DELETE_USER</li>
 * </ul>
 *
 * <p>Tenant lifecycle commands (create/suspend/reactivate) use RabbitMQ sync
 * because they are rare and need immediate confirmation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCommandKafkaConsumer {

    private final UserService  userService;
    private final ObjectMapper objectMapper;


    @KafkaListener(
            topics = KafkaTopics.USER_COMMANDS,
            groupId = "routify-identity-service-user-commands",
            containerFactory = "userCommandKafkaListenerContainerFactory"
    )
    public void onUserCommand(String commandJson, Acknowledgment ack) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(commandJson, new TypeReference<>() {});
            String command    = str(envelope.get("command"));
            UUID   tenantId   = parseUuid(envelope.get("tenantId"));

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = envelope.containsKey("payload")
                    ? (Map<String, Object>) envelope.get("payload")
                    : envelope;

            log.info("User command received: command={} tenantId={}", command, tenantId);

            switch (command) {
                case "CREATE_USER" -> {
                    var req = new AuthDto.CreateUserRequest(
                            str(payload.get("username")),
                            str(payload.get("email")),
                            str(payload.getOrDefault("password", UUID.randomUUID().toString())),
                            UserRole.valueOf(str(payload.getOrDefault("role", "VIEWER")).toUpperCase())
                    );
                    userService.create(req, tenantId);
                }
                case "UPDATE_USER" -> {
                    UUID id = parseUuid(payload.get("id"));
                    UserRole role = payload.get("role") != null
                            ? UserRole.valueOf(str(payload.get("role")).toUpperCase()) : null;
                    var req = new AuthDto.UpdateUserRequest(
                            str(payload.get("username")),
                            str(payload.get("email")),
                            role
                    );
                    userService.update(id, tenantId, req);
                }
                case "DELETE_USER" -> userService.delete(parseUuid(payload.get("id")), tenantId);
                default -> log.warn("Unknown user command: {}", command);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process user command: {} — {}", commandJson, e.getMessage(), e);
        }
    }

    private UUID parseUuid(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        return UUID.fromString(val.toString());
    }

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }
}

