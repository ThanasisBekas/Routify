package io.routify.identity.messaging;

import io.routify.common.event.CommandEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.identity.domain.ProcessedCommand;
import io.routify.identity.repository.ProcessedCommandRepository;
import io.routify.identity.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka command consumer for webhook subscription lifecycle events.
 *
 * <p>Consumes {@link CommandEvent.CreateWebhook}, {@link CommandEvent.UpdateWebhook},
 * and {@link CommandEvent.DeleteWebhook} from {@link KafkaTopics#WEBHOOK_COMMANDS}.
 *
 * <p><b>Idempotency:</b> Every command's {@code commandId} is checked against the
 * {@code processed_command} table before execution and recorded after success.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookCommandConsumer {

    private final WebhookService webhookService;
    private final ProcessedCommandRepository processedCommandRepo;

    @KafkaListener(
            topics = KafkaTopics.WEBHOOK_COMMANDS,
            groupId = "routify-identity-service-webhook-commands",
            containerFactory = "userCommandKafkaListenerContainerFactory"
    )
    public void onWebhookCommand(CommandEvent cmd, Acknowledgment ack) {
        if (isDuplicate(cmd)) { ack.acknowledge(); return; }
        try {
            log.info("Webhook command received: type={} tenantId={}",
                    cmd.getClass().getSimpleName(), cmd.tenantId());

            switch (cmd) {
                case CommandEvent.CreateWebhook c -> {
                    UUID createdBy = c.actor() != null ? parseUuidSafe(c.actor()) : null;
                    webhookService.create(c.tenantId(), c.name(), c.url(),
                            c.eventTypes(), createdBy);
                }
                case CommandEvent.UpdateWebhook c -> webhookService.update(
                        c.webhookId(), c.tenantId(), c.name(), c.url(), c.eventTypes());
                case CommandEvent.DeleteWebhook c -> webhookService.delete(
                        c.webhookId(), c.tenantId());
                default -> log.warn("Unexpected command type on webhook topic: {}",
                        cmd.getClass().getSimpleName());
            }

            markProcessed(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process webhook command: type={} — {}",
                    cmd.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private boolean isDuplicate(CommandEvent cmd) {
        if (cmd.commandId() == null) return false;
        if (processedCommandRepo.existsById(cmd.commandId())) {
            log.info("Duplicate webhook command skipped: type={} commandId={}",
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
            log.debug("Webhook command already recorded: commandId={}", cmd.commandId());
        }
    }

    private static UUID parseUuidSafe(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

