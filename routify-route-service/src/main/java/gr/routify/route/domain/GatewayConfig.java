package gr.routify.route.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for durable gateway configuration storage.
 *
 * <p>Each row stores one configuration section (e.g. "global", "cors",
 * "security_headers") as a JSONB blob. The "global" key holds the complete
 * merged {@code GatewayConfigDto} and is the authoritative source of truth
 * loaded by the gateway on every startup and config-change event.
 */
@Entity
@Table(
    name = "gateway_config",
    schema = "routify",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_gateway_config_key",
        columnNames = {"config_key", "tenant_id"}
    )
)
public class GatewayConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Logical config key. "global" = complete merged config, or a section name. */
    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    /** Null = platform-wide config. Non-null = tenant-specific override. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** The configuration as JSONB — deserialized by the consumer (admin-api / gateway). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_value", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configValue;

    /** Optimistic lock version — incremented on every save. */
    @Column(nullable = false)
    private Long version = 1L;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GatewayConfig() {}

    public GatewayConfig(String configKey, UUID tenantId, Map<String, Object> configValue, String updatedBy) {
        this.configKey   = configKey;
        this.tenantId    = tenantId;
        this.configValue = configValue;
        this.updatedBy   = updatedBy;
    }

    // ─── Getters / Setters ───────────────────────────────────────────────────

    public UUID getId()                          { return id; }
    public String getConfigKey()                 { return configKey; }
    public UUID getTenantId()                    { return tenantId; }
    public Map<String, Object> getConfigValue()  { return configValue; }
    public Long getVersion()                     { return version; }
    public String getUpdatedBy()                 { return updatedBy; }
    public Instant getUpdatedAt()                { return updatedAt; }
    public Instant getCreatedAt()                { return createdAt; }

    public void setConfigValue(Map<String, Object> configValue) { this.configValue = configValue; }
    public void setUpdatedBy(String updatedBy)                  { this.updatedBy = updatedBy; }
    public void incrementVersion()                              { this.version = (this.version == null ? 1L : this.version) + 1; }
}

