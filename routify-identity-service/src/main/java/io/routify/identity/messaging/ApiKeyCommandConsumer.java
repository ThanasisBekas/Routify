package io.routify.identity.messaging;

import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.identity.domain.ProcessedCommand;
import io.routify.identity.repository.ProcessedCommandRepository;
import io.routify.identity.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka command consumer for API key lifecycle events.
 *
 * <p>Consumes {@link CommandEvent.CreateApiKey}, {@link CommandEvent.RevokeApiKey},
 * and {@link CommandEvent.RotateApiKey} from {@link KafkaTopics#APIKEY_COMMANDS}.
 *
 * <p><b>Idempotency:</b> Every command's {@code commandId} is checked against the
 * {@code processed_command} table before execution and recorded after success.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyCommandConsumer {

    private final ApiKeyService              apiKeyService;
    private final ProcessedCommandRepository processedCommandRepo;

    @KafkaListener(
            topics = KafkaTopics.APIKEY_COMMANDS,
            groupId = "routify-identity-service-apikey-commands",
            containerFactory = "userCommandKafkaListenerContainerFactory"
    )
    public void onApiKeyCommand(CommandEvent cmd, Acknowledgment ack) {
        if (isDuplicate(cmd)) { ack.acknowledge(); return; }
        try {
            log.info("API key command received: type={} tenantId={}",
                    cmd.getClass().getSimpleName(), cmd.tenantId());

            switch (cmd) {
                case CommandEvent.CreateApiKey c -> apiKeyService.create(
                        c.tenantId(), c.userId(), c.name(), c.role(),
                        c.email(), c.expiresAt(), c.requestedBy());
                case CommandEvent.RevokeApiKey c -> apiKeyService.revoke(
                        c.apiKeyId(), c.tenantId(), c.requestedBy());
                case CommandEvent.RotateApiKey c -> apiKeyService.rotate(
                        c.apiKeyId(), c.tenantId(), c.requestedBy());
                default -> log.warn("Unexpected command type on apikey topic: {}",
                        cmd.getClass().getSimpleName());
            }

            markProcessed(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process API key command: type={} — {}",
                    cmd.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private boolean isDuplicate(CommandEvent cmd) {
        if (cmd.commandId() == null) return false;
        if (processedCommandRepo.existsById(cmd.commandId())) {
            log.info("Duplicate API key command skipped: type={} commandId={}",
                    cmd.getClass().getSimpleName(), cmd.commandId());
            return true;
        }
        return false;
    }

    private void markProcessed(CommandEvent cmd) {
        if (cmd.commandId() == null) return;
        try {
            processedCommandRepo.save(new ProcessedCommand(cmd.commandId(), cmd.getClass().getSimpleName()));
        } catch (DataIntegrityViolationException e) {
            log.debug("API key command already recorded: commandId={}", cmd.commandId());
        }
    }
}

