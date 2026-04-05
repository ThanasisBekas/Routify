package io.routify.identity.messaging;

import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.identity.domain.ProcessedCommand;
import io.routify.identity.dto.AuthDto;
import io.routify.identity.repository.ProcessedCommandRepository;
import io.routify.identity.service.UserService;
import io.routify.common.domain.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
 *
 * <p><b>Idempotency:</b> Every command's {@code commandId} is checked against the
 * {@code processed_command} table before execution and recorded after success.
 * Duplicate commands are safely skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCommandKafkaConsumer {

    private final UserService                userService;
    private final ProcessedCommandRepository processedCommandRepo;

    @KafkaListener(
            topics = KafkaTopics.USER_COMMANDS,
            groupId = "routify-identity-service-user-commands",
            containerFactory = "userCommandKafkaListenerContainerFactory"
    )
    public void onUserCommand(CommandEvent cmd, Acknowledgment ack) {
        if (isDuplicate(cmd)) { ack.acknowledge(); return; }
        try {
            log.info("User command received: type={} tenantId={}",
                    cmd.getClass().getSimpleName(), cmd.tenantId());

            switch (cmd) {
                case CommandEvent.CreateUser c -> {
                    var req = new AuthDto.CreateUserRequest(
                            c.username(), c.email(),
                            c.password() != null ? c.password() : UUID.randomUUID().toString(),
                            c.role() != null ? c.role() : UserRole.VIEWER);
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

            markProcessed(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process user command: type={} — {}", cmd.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    // ─── Idempotency helpers ─────────────────────────────────────────────────

    private boolean isDuplicate(CommandEvent cmd) {
        if (cmd.commandId() == null) {
            log.warn("Command has no commandId — skipping idempotency check: type={}", cmd.getClass().getSimpleName());
            return false;
        }
        if (processedCommandRepo.existsById(cmd.commandId())) {
            log.info("Duplicate command skipped: type={} commandId={}", cmd.getClass().getSimpleName(), cmd.commandId());
            return true;
        }
        return false;
    }

    private void markProcessed(CommandEvent cmd) {
        if (cmd.commandId() == null) return;
        try {
            processedCommandRepo.save(new ProcessedCommand(cmd.commandId(), cmd.getClass().getSimpleName()));
        } catch (DataIntegrityViolationException e) {
            log.debug("Command already recorded (concurrent duplicate): commandId={}", cmd.commandId());
        }
    }
}
