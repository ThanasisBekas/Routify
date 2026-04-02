package gr.routify.identity.messaging;

import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka command consumer for routify-identity-service.
 *
 * <p>Consumes user {@link CommandEvent}s published by routify-admin-api.
 * Pattern matching on the sealed {@link CommandEvent} type replaces the old
 * {@code Map<String,Object>} / {@code switch(command)} pattern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCommandKafkaConsumer {

    private final UserService userService;

    @KafkaListener(
            topics = KafkaTopics.USER_COMMANDS,
            groupId = "routify-identity-service-user-commands",
            containerFactory = "userCommandKafkaListenerContainerFactory"
    )
    public void onUserCommand(CommandEvent cmd, Acknowledgment ack) {
        try {
            log.info("User command received: type={} tenantId={}",
                    cmd.getClass().getSimpleName(), cmd.tenantId());

            switch (cmd) {
                case CommandEvent.CreateUser c -> {
                    var req = new AuthDto.CreateUserRequest(
                            c.username(), c.email(),
                            c.password() != null ? c.password() : UUID.randomUUID().toString(),
                            c.role() != null ? c.role() : gr.routify.common.domain.UserRole.VIEWER);
                    userService.create(req, c.tenantId());
                }
                case CommandEvent.UpdateUser c -> {
                    var req = new AuthDto.UpdateUserRequest(c.username(), c.email(), c.role());
                    userService.update(c.id(), c.tenantId(), req);
                }
                case CommandEvent.DeleteUser c -> userService.delete(c.id(), c.tenantId());
                default -> log.warn("Unexpected command type on user topic: {}",
                        cmd.getClass().getSimpleName());
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process user command: type={} — {}", cmd.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
}
