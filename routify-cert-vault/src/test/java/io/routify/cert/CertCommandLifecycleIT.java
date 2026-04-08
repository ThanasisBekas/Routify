package io.routify.cert;

import io.routify.cert.domain.CertGroup;
import io.routify.common.event.CommandEvent;
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
 * Integration tests for the certificate upload/revoke lifecycle via Kafka commands.
 *
 * <p>Sends {@link CommandEvent}s to the cert commands topic and verifies
 * that cert-vault processes them correctly — groups created, certs persisted,
 * encrypted material stored, and outbox events generated.
 */
class CertCommandLifecycleIT extends CertVaultIntegrationBase {

    @Test
    @DisplayName("CreateCertGroup command creates a cert group in the database")
    void createCertGroupCommand_createsGroup() throws Exception {
        UUID commandId = UUID.randomUUID();
        CommandEvent cmd = new CommandEvent.CreateCertGroup(
                commandId, TENANT_ID, ACTOR, Instant.now(),
                "test-group-1", "Test Group", "A test certificate group");

        sendCommand(KafkaTopics.CERT_COMMANDS, cmd);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var groups = groupRepository.findAll();
                    assertThat(groups).isNotEmpty();
                    CertGroup group = groups.stream()
                            .filter(g -> "test-group-1".equals(g.getLogicalId()))
                            .findFirst()
                            .orElse(null);
                    assertThat(group).isNotNull();
                    assertThat(group.getAlias()).isEqualTo("Test Group");
                    assertThat(group.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(group.isActive()).isTrue();
                });
    }

    @Test
    @DisplayName("Duplicate commands are idempotent — processed once, second skipped")
    void duplicateCommand_isSkipped() throws Exception {
        UUID commandId = UUID.randomUUID();
        CommandEvent cmd = new CommandEvent.CreateCertGroup(
                commandId, TENANT_ID, ACTOR, Instant.now(),
                "idempotent-group", "Idempotent Group", "Should be created once");

        // Send the same command twice
        sendCommand(KafkaTopics.CERT_COMMANDS, cmd);
        sendCommand(KafkaTopics.CERT_COMMANDS, cmd);

        // Wait for processing
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    // Verify command was marked as processed
                    assertThat(processedCommandRepository.existsById(commandId)).isTrue();
                    // Verify only one group was created
                    var groups = groupRepository.findAll().stream()
                            .filter(g -> "idempotent-group".equals(g.getLogicalId()))
                            .toList();
                    assertThat(groups).hasSize(1);
                });
    }

    @Test
    @DisplayName("UploadCertificate command creates a cert with encrypted material and outbox event")
    void uploadCertificateCommand_createsCertWithEncryptedMaterial() throws Exception {
        // First create a group (required for upload)
        UUID groupCmdId = UUID.randomUUID();
        CommandEvent groupCmd = new CommandEvent.CreateCertGroup(
                groupCmdId, TENANT_ID, ACTOR, Instant.now(),
                "upload-test-group", "Upload Test Group", "For cert upload IT");
        sendCommand(KafkaTopics.CERT_COMMANDS, groupCmd);

        // Wait for group to be created
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> groupRepository.findAll().stream()
                        .anyMatch(g -> "upload-test-group".equals(g.getLogicalId())));

        UUID groupId = groupRepository.findAll().stream()
                .filter(g -> "upload-test-group".equals(g.getLogicalId()))
                .findFirst()
                .orElseThrow()
                .getId();

        // Now upload a certificate
        UUID uploadCmdId = UUID.randomUUID();
        String certPem = "-----BEGIN CERTIFICATE-----\nMIIBxTCCAWugAwIBAgIJALRi\n-----END CERTIFICATE-----\n";

        CommandEvent uploadCmd = new CommandEvent.UploadCertificate(
                uploadCmdId, TENANT_ID, ACTOR, Instant.now(),
                groupId, "primary", "test-cert-1", "Test certificate", "PEM",
                certPem, null);

        sendCommand(KafkaTopics.CERT_COMMANDS, uploadCmd);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var certs = certRepository.findAll();
                    assertThat(certs).isNotEmpty();
                    var cert = certs.stream()
                            .filter(c -> "test-cert-1".equals(c.getAlias()))
                            .findFirst()
                            .orElse(null);
                    assertThat(cert).isNotNull();
                    assertThat(cert.getTenantId()).isEqualTo(TENANT_ID);
                    // Encrypted material should be stored (not plain PEM)
                    assertThat(cert.getCertDataEnc()).isNotBlank();
                    assertThat(cert.getEncIv()).isNotBlank();
                    assertThat(cert.getEncTag()).isNotBlank();
                    // Outbox event should be generated
                    var outboxEvents = outboxEventRepository.findAll();
                    assertThat(outboxEvents).isNotEmpty();
                });
    }

    @Test
    @DisplayName("RevokeCertificate command changes cert status to REVOKED")
    void revokeCertificateCommand_revokesCert() throws Exception {
        // Create group + upload cert first
        UUID groupCmdId = UUID.randomUUID();
        CommandEvent groupCmd = new CommandEvent.CreateCertGroup(
                groupCmdId, TENANT_ID, ACTOR, Instant.now(),
                "revoke-test-group", "Revoke Test Group", "For revoke IT");
        sendCommand(KafkaTopics.CERT_COMMANDS, groupCmd);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> groupRepository.findAll().stream()
                        .anyMatch(g -> "revoke-test-group".equals(g.getLogicalId())));

        UUID groupId = groupRepository.findAll().stream()
                .filter(g -> "revoke-test-group".equals(g.getLogicalId()))
                .findFirst()
                .orElseThrow()
                .getId();

        UUID uploadCmdId = UUID.randomUUID();
        CommandEvent uploadCmd = new CommandEvent.UploadCertificate(
                uploadCmdId, TENANT_ID, ACTOR, Instant.now(),
                groupId, null, "revoke-cert", "Cert to revoke", "PEM",
                "-----BEGIN CERTIFICATE-----\nMIIBxTCCAW\n-----END CERTIFICATE-----\n", null);
        sendCommand(KafkaTopics.CERT_COMMANDS, uploadCmd);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> certRepository.findAll().stream()
                        .anyMatch(c -> "revoke-cert".equals(c.getAlias())));

        UUID certId = certRepository.findAll().stream()
                .filter(c -> "revoke-cert".equals(c.getAlias()))
                .findFirst()
                .orElseThrow()
                .getId();

        // Now revoke
        UUID revokeCmdId = UUID.randomUUID();
        CommandEvent revokeCmd = new CommandEvent.RevokeCertificate(
                revokeCmdId, TENANT_ID, ACTOR, Instant.now(), certId);
        sendCommand(KafkaTopics.CERT_COMMANDS, revokeCmd);

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var cert = certRepository.findById(certId).orElseThrow();
                    assertThat(cert.getStatus().name()).isEqualTo("REVOKED");
                });
    }
}

