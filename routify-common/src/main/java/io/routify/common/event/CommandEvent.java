package io.routify.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.routify.common.domain.FilterType;
import io.routify.common.domain.TenantPlan;
import io.routify.common.domain.UserRole;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sealed interface representing all write commands sent over Kafka.
 *
 * <p>Commands flow from routify-admin-api to the owning microservice:
 * <ul>
 *   <li>{@link KafkaTopics#ROUTE_COMMANDS}   → routify-route-service</li>
 *   <li>{@link KafkaTopics#FILTER_COMMANDS}  → routify-route-service</li>
 *   <li>{@link KafkaTopics#USER_COMMANDS}    → routify-identity-service</li>
 *   <li>{@link KafkaTopics#AUTH_COMMANDS}    → routify-identity-service</li>
 *   <li>{@link KafkaTopics#APIKEY_COMMANDS}  → routify-identity-service</li>
 *   <li>{@link KafkaTopics#CERT_COMMANDS}    → routify-cert-vault</li>
 * </ul>
 *
 * <h2>Design</h2>
 * Each command is a strongly-typed record that mirrors the shape of the service
 * method it triggers. The {@code commandId} field is the idempotency key and
 * must be stored / checked by consumers that require exactly-once semantics.
 *
 * <h2>Wire format</h2>
 * Jackson serialises the {@code "type"} discriminator automatically via
 * {@link JsonTypeInfo}. All enum values map 1-to-1 with the command names
 * used in the old {@code Map<String,Object>} envelope, so existing consumers
 * that haven't yet migrated will still receive the {@code "type"} field and
 * can treat it as the former {@code "command"} key.
 *
 * <h2>Consumer usage</h2>
 * <pre>{@code
 * CommandEvent cmd = objectMapper.readValue(json, CommandEvent.class);
 * switch (cmd) {
 *     case CommandEvent.CreateRoute c  -> routeService.create(c, c.tenantId(), c.requestedBy());
 *     case CommandEvent.UpdateRoute c  -> routeService.update(c.id(), c.tenantId(), c);
 *     case CommandEvent.DeleteRoute c  -> routeService.delete(c.id(), c.tenantId());
 *     // ...
 * }
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = CommandEvent.Unknown.class)
@JsonSubTypes({
    // ─── Route commands ───────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.CreateRoute.class,        name = "CREATE_ROUTE"),
    @JsonSubTypes.Type(value = CommandEvent.UpdateRoute.class,        name = "UPDATE_ROUTE"),
    @JsonSubTypes.Type(value = CommandEvent.ActivateRoute.class,      name = "ACTIVATE_ROUTE"),
    @JsonSubTypes.Type(value = CommandEvent.DeactivateRoute.class,    name = "DEACTIVATE_ROUTE"),
    @JsonSubTypes.Type(value = CommandEvent.DeleteRoute.class,        name = "DELETE_ROUTE"),
    // ─── Filter commands ──────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.AttachFilter.class,       name = "ATTACH_FILTER"),
    @JsonSubTypes.Type(value = CommandEvent.DetachFilter.class,       name = "DETACH_FILTER"),
    @JsonSubTypes.Type(value = CommandEvent.CreateFilter.class,       name = "CREATE_FILTER"),
    @JsonSubTypes.Type(value = CommandEvent.UpdateFilter.class,       name = "UPDATE_FILTER"),
    @JsonSubTypes.Type(value = CommandEvent.DeleteFilter.class,       name = "DELETE_FILTER"),
    // ─── User commands ────────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.CreateUser.class,         name = "CREATE_USER"),
    @JsonSubTypes.Type(value = CommandEvent.UpdateUser.class,         name = "UPDATE_USER"),
    @JsonSubTypes.Type(value = CommandEvent.DeleteUser.class,         name = "DELETE_USER"),
    // ─── Auth commands ────────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.Logout.class,             name = "LOGOUT"),
    // ─── Certificate commands ─────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.UploadCertificate.class,          name = "UPLOAD_CERTIFICATE"),
    @JsonSubTypes.Type(value = CommandEvent.RevokeCertificate.class,          name = "REVOKE_CERTIFICATE"),
    @JsonSubTypes.Type(value = CommandEvent.DeleteCertificate.class,          name = "DELETE_CERTIFICATE"),
    @JsonSubTypes.Type(value = CommandEvent.MapCertificateToGateway.class,    name = "MAP_CERTIFICATE_TO_GATEWAY"),
    @JsonSubTypes.Type(value = CommandEvent.UnmapCertificateFromGateway.class,name = "UNMAP_CERTIFICATE_FROM_GATEWAY"),
    // ─── Cert-group commands ──────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.CreateCertGroup.class,    name = "CREATE_CERT_GROUP"),
    @JsonSubTypes.Type(value = CommandEvent.UpdateCertGroup.class,    name = "UPDATE_CERT_GROUP"),
    @JsonSubTypes.Type(value = CommandEvent.ArchiveCertGroup.class,   name = "ARCHIVE_CERT_GROUP"),
    @JsonSubTypes.Type(value = CommandEvent.DeleteCertGroup.class,    name = "DELETE_CERT_GROUP"),
    @JsonSubTypes.Type(value = CommandEvent.AddCertToGroup.class,     name = "ADD_CERT_TO_GROUP"),
    @JsonSubTypes.Type(value = CommandEvent.RemoveCertFromGroup.class,name = "REMOVE_CERT_FROM_GROUP"),
    // ─── Tenant Commands ──────────────────────────────────────────────────────
    // Sent synchronously over RabbitMQ (not Kafka) because tenant lifecycle
    // changes are rare admin actions that need immediate confirmation.
    @JsonSubTypes.Type(value = CommandEvent.CreateTenant.class,      name = "CREATE_TENANT"),
    @JsonSubTypes.Type(value = CommandEvent.UpdateTenant.class,      name = "UPDATE_TENANT"),
    @JsonSubTypes.Type(value = CommandEvent.SuspendTenant.class,     name = "SUSPEND_TENANT"),
    @JsonSubTypes.Type(value = CommandEvent.ReactivateTenant.class,  name = "REACTIVATE_TENANT"),
    // ─── Gateway config commands (also sync over RabbitMQ) ───────────────────
    @JsonSubTypes.Type(value = CommandEvent.SaveGatewayConfig.class, name = "SAVE_GATEWAY_CONFIG"),
    // ─── API Key commands ──────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = CommandEvent.CreateApiKey.class,  name = "CREATE_API_KEY"),
    @JsonSubTypes.Type(value = CommandEvent.RevokeApiKey.class,  name = "REVOKE_API_KEY"),
    @JsonSubTypes.Type(value = CommandEvent.RotateApiKey.class,  name = "ROTATE_API_KEY"),
})
public sealed interface CommandEvent
        permits
            CommandEvent.CreateRoute,
            CommandEvent.UpdateRoute,
            CommandEvent.ActivateRoute,
            CommandEvent.DeactivateRoute,
            CommandEvent.DeleteRoute,
            CommandEvent.AttachFilter,
            CommandEvent.DetachFilter,
            CommandEvent.CreateFilter,
            CommandEvent.UpdateFilter,
            CommandEvent.DeleteFilter,
            CommandEvent.CreateUser,
            CommandEvent.UpdateUser,
            CommandEvent.DeleteUser,
            CommandEvent.Logout,
            CommandEvent.UploadCertificate,
            CommandEvent.RevokeCertificate,
            CommandEvent.DeleteCertificate,
            CommandEvent.MapCertificateToGateway,
            CommandEvent.UnmapCertificateFromGateway,
            CommandEvent.CreateCertGroup,
            CommandEvent.UpdateCertGroup,
            CommandEvent.ArchiveCertGroup,
            CommandEvent.DeleteCertGroup,
            CommandEvent.AddCertToGroup,
            CommandEvent.RemoveCertFromGroup,
            CommandEvent.CreateTenant,
            CommandEvent.UpdateTenant,
            CommandEvent.SuspendTenant,
            CommandEvent.ReactivateTenant,
            CommandEvent.SaveGatewayConfig,
            CommandEvent.CreateApiKey,
            CommandEvent.RevokeApiKey,
            CommandEvent.RotateApiKey,
            CommandEvent.Unknown {

    /** Idempotency key — generated by the producer, checked by consumers. */
    UUID commandId();

    /** Tenant scope. {@code null} for system-wide commands (e.g. {@link Logout}). */
    UUID tenantId();

    /** User ID or service name that issued the command. */
    String requestedBy();

    /** Timestamp at which the command was issued. */
    Instant issuedAt();

    // ─── Route Commands ───────────────────────────────────────────────────────

    record CreateRoute(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            String name,
            String description,
            String pathPattern,
            String methods,
            String upstreamUri,
            String stripPrefix,
            Map<String, Object> extraConfig
    ) implements CommandEvent {}

    record UpdateRoute(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id,
            String name,
            String description,
            String pathPattern,
            String methods,
            String upstreamUri,
            String stripPrefix,
            Map<String, Object> extraConfig
    ) implements CommandEvent {}

    record ActivateRoute(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    record DeactivateRoute(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    record DeleteRoute(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    // ─── Filter Commands ──────────────────────────────────────────────────────

    record AttachFilter(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   routeId,
            UUID   filterId,
            int    order,
            String phase
    ) implements CommandEvent {}

    record DetachFilter(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   routeId,
            UUID   filterId
    ) implements CommandEvent {}

    record CreateFilter(
            UUID       commandId,
            UUID       tenantId,
            String     requestedBy,
            Instant    issuedAt,
            String     name,
            String     description,
            FilterType filterType,
            Map<String, Object> config,
            Map<String, Object> gatewayConfigRef
    ) implements CommandEvent {}

    record UpdateFilter(
            UUID       commandId,
            UUID       tenantId,
            String     requestedBy,
            Instant    issuedAt,
            UUID       id,
            String     name,
            String     description,
            Map<String, Object> config,
            Map<String, Object> gatewayConfigRef
    ) implements CommandEvent {}

    record DeleteFilter(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    // ─── User Commands ────────────────────────────────────────────────────────

    record CreateUser(
            UUID     commandId,
            UUID     tenantId,
            String   requestedBy,
            Instant  issuedAt,
            String   username,
            String   email,
            String   password,
            UserRole role
    ) implements CommandEvent {}

    record UpdateUser(
            UUID     commandId,
            UUID     tenantId,
            String   requestedBy,
            Instant  issuedAt,
            UUID     id,
            String   username,
            String   email,
            UserRole role
    ) implements CommandEvent {}

    record DeleteUser(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    // ─── Auth Commands ────────────────────────────────────────────────────────

    record Logout(
            UUID   commandId,
            UUID   tenantId,       // null — logout is session-scoped, not tenant-scoped
            String requestedBy,
            Instant issuedAt,
            String refreshToken
    ) implements CommandEvent {}

    // ─── Certificate Commands ─────────────────────────────────────────────────

    record UploadCertificate(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   groupId,
            String memberAlias,
            String alias,
            String description,
            String format,         // "PEM" | "PKCS12"
            String certPem,
            String privateKey      // nullable
    ) implements CommandEvent {}

    record RevokeCertificate(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    record DeleteCertificate(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    record MapCertificateToGateway(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id,
            String gatewayTlsLogicalId
    ) implements CommandEvent {}

    record UnmapCertificateFromGateway(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    // ─── Cert-Group Commands ──────────────────────────────────────────────────

    record CreateCertGroup(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            String logicalId,
            String alias,
            String description
    ) implements CommandEvent {}

    record UpdateCertGroup(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id,
            String alias,
            String description
    ) implements CommandEvent {}

    record ArchiveCertGroup(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    record DeleteCertGroup(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   id
    ) implements CommandEvent {}

    record AddCertToGroup(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   groupId,
            UUID   certId,
            String memberAlias
    ) implements CommandEvent {}

    record RemoveCertFromGroup(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   groupId,
            UUID   certId
    ) implements CommandEvent {}

    // ─── Tenant Commands (RabbitMQ sync) ─────────────────────────────────────

    record CreateTenant(
            UUID       commandId,
            UUID       tenantId,   // null — assigned by the service on creation
            String     requestedBy,
            Instant    issuedAt,
            String     name,
            String     slug,
            TenantPlan plan,
            String     contactEmail
    ) implements CommandEvent {}

    record UpdateTenant(
            UUID       commandId,
            UUID       tenantId,
            String     requestedBy,
            Instant    issuedAt,
            String     name,
            TenantPlan plan,
            String     contactEmail
    ) implements CommandEvent {}

    record SuspendTenant(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            String reason
    ) implements CommandEvent {}

    record ReactivateTenant(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt
    ) implements CommandEvent {}

    // ─── Gateway Config Commands (RabbitMQ sync) ─────────────────────────────

    record SaveGatewayConfig(
            UUID                commandId,
            UUID                tenantId,   // null — gateway config is global
            String              requestedBy,
            Instant             issuedAt,
            String              section,
            Map<String, Object> config
    ) implements CommandEvent {}

    // ─── API Key Commands ─────────────────────────────────────────────────────

    record CreateApiKey(
            UUID     commandId,
            UUID     tenantId,
            String   requestedBy,
            Instant  issuedAt,
            UUID     userId,
            String   name,
            UserRole role,
            String   email,
            Instant  expiresAt
    ) implements CommandEvent {}

    record RevokeApiKey(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   apiKeyId
    ) implements CommandEvent {}

    record RotateApiKey(
            UUID   commandId,
            UUID   tenantId,
            String requestedBy,
            Instant issuedAt,
            UUID   apiKeyId
    ) implements CommandEvent {}

    /**
     * Fallback subtype used when the {@code "type"} discriminator is absent or unrecognised.
     * Prevents {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException} from being
     * thrown during deserialisation (e.g. legacy messages or services that haven't yet been
     * rebuilt with the latest {@code routify-common}).
     */
    record Unknown() implements CommandEvent {
        @Override public UUID commandId()    { return null; }
        @Override public UUID tenantId()     { return null; }
        @Override public String requestedBy(){ return null; }
        @Override public Instant issuedAt()  { return null; }
    }
}



