package gr.routify.cert.messaging;

import gr.routify.cert.domain.ProcessedCommand;
import gr.routify.cert.repository.ProcessedCommandRepository;
import gr.routify.cert.service.CertGroupService;
import gr.routify.cert.service.CertificateVaultService;
import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka command consumer for routify-cert-vault.
 *
 * <p>Consumes certificate and certificate-group {@link CommandEvent}s published by routify-admin-api.
 * Strongly-typed record pattern matching replaces the old {@code Map<String,Object>} / string
 * command dispatch. Each record's fields are accessed directly — no casting or null-safe
 * helper methods needed.
 *
 * <p><b>Idempotency:</b> Every command's {@code commandId} is checked against the
 * {@code processed_command} table before execution and recorded after success.
 * Duplicate commands are safely skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertCommandKafkaConsumer {

    private final CertificateVaultService    vaultService;
    private final CertGroupService           groupService;
    private final ProcessedCommandRepository processedCommandRepo;

    @KafkaListener(
            topics = KafkaTopics.CERT_COMMANDS,
            groupId = "routify-cert-vault-commands",
            containerFactory = "certCommandKafkaListenerContainerFactory"
    )
    public void onCertCommand(CommandEvent cmd, Acknowledgment ack) {
        if (isDuplicate(cmd)) { ack.acknowledge(); return; }
        try {
            log.info("Cert command received: type={} tenantId={} by={}",
                    cmd.getClass().getSimpleName(), cmd.tenantId(), cmd.requestedBy());

            switch (cmd) {
                case CommandEvent.UploadCertificate c -> vaultService.uploadCertificate(
                        c.tenantId(), c.groupId(), c.memberAlias(),
                        c.alias(), c.description(),
                        c.format() != null ? c.format() : "PEM",
                        c.certPem(), c.privateKey(), c.requestedBy());

                case CommandEvent.RevokeCertificate c ->
                        vaultService.revokeCertificate(c.id(), c.tenantId(), c.requestedBy());

                case CommandEvent.DeleteCertificate c ->
                        vaultService.deleteCertificate(c.id(), c.tenantId(), c.requestedBy());

                case CommandEvent.MapCertificateToGateway c ->
                        vaultService.mapToGateway(c.id(), c.tenantId(), c.gatewayTlsLogicalId(), c.requestedBy());

                case CommandEvent.UnmapCertificateFromGateway c ->
                        vaultService.mapToGateway(c.id(), c.tenantId(), null, c.requestedBy());

                case CommandEvent.CreateCertGroup c -> groupService.createGroup(
                        c.tenantId(), c.logicalId(), c.alias(), c.description(), c.requestedBy());

                case CommandEvent.UpdateCertGroup c -> groupService.updateGroup(
                        c.id(), c.tenantId(), c.alias(), c.description(), c.requestedBy());

                case CommandEvent.ArchiveCertGroup c ->
                        groupService.archiveGroup(c.id(), c.tenantId(), c.requestedBy());

                case CommandEvent.DeleteCertGroup c ->
                        groupService.deleteGroup(c.id(), c.tenantId(), c.requestedBy());

                case CommandEvent.AddCertToGroup c -> groupService.addCertificateToGroup(
                        c.groupId(), c.certId(), c.memberAlias(), c.tenantId(), c.requestedBy());

                case CommandEvent.RemoveCertFromGroup c -> groupService.removeCertificateFromGroup(
                        c.groupId(), c.certId(), c.tenantId(), c.requestedBy());

                default -> log.warn("Unexpected command type on cert topic: {}",
                        cmd.getClass().getSimpleName());
            }
            markProcessed(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process cert command: type={} — {}", cmd.getClass().getSimpleName(), e.getMessage(), e);
            // Don't ack — let Kafka retry or route to DLQ
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
