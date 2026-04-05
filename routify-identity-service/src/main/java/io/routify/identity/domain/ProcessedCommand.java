package io.routify.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency ledger entry for processed Kafka commands.
 *
 * <p>Each successfully processed {@code CommandEvent.commandId()} is recorded here.
 * Before executing a command, the consumer checks for an existing row — if found,
 * the command is a duplicate and is safely skipped.
 *
 * <p>Rows older than 7 days are safe to purge (Kafka retention is typically shorter).
 */
@Entity
@Table(name = "processed_command", schema = "routify_identity")
public class ProcessedCommand {

    @Id
    @Column(name = "command_id", updatable = false, nullable = false)
    private UUID commandId;

    @Column(name = "command_type", nullable = false, length = 100)
    private String commandType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedCommand() {}

    public ProcessedCommand(UUID commandId, String commandType) {
        this.commandId   = commandId;
        this.commandType = commandType;
        this.processedAt = Instant.now();
    }

    public UUID getCommandId()      { return commandId; }
    public String getCommandType()  { return commandType; }
    public Instant getProcessedAt() { return processedAt; }
}

