package io.routify.route.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.route.domain.GatewayConfig;
import io.routify.route.outbox.OutboxEventStore;
import io.routify.route.repository.GatewayConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Durable gateway configuration service.
 *
 * <h3>Storage Strategy — Rollout-Safe Persistence</h3>
 * <p>Config is stored as JSONB in the {@code routify.gateway_config} PostgreSQL table.
 * This means every gateway pod restart, rolling rollout, or Redis flush is
 * completely safe — the config is always reloaded from the DB.
 *
 * <h3>Flow on Write (Admin API → Gateway)</h3>
 * <ol>
 *   <li>Admin saves config via dashboard</li>
 *   <li>Admin API calls {@code PUT /api/v1/gateway-config} on route-service</li>
 *   <li>Route-service writes to {@code routify.gateway_config} table (same TX)</li>
 *   <li>Route-service stores a {@code GatewayConfigChanged} outbox event</li>
 *   <li>OutboxPoller publishes the event to {@code routify.gateway.config} Kafka topic</li>
 *   <li>All running gateway pods consume the event and reload config from DB</li>
 * </ol>
 *
 * <h3>Flow on Startup / Rollout</h3>
 * <ol>
 *   <li>New gateway pod starts (ApplicationReadyEvent)</li>
 *   <li>Calls route-service {@code GET /api/v1/gateway-config} (same endpoint used for reload)</li>
 *   <li>Route-service returns the full JSONB config from PostgreSQL</li>
 *   <li>Gateway applies config — no Redis dependency for startup</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayConfigService {

    /** The key used to store the complete merged gateway config. */
    public static final String GLOBAL_KEY = "global";

    private final GatewayConfigRepository configRepository;
    private final OutboxEventStore         outboxStore;
    private final ObjectMapper             objectMapper;

    // ─── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns the complete gateway configuration JSON map.
     * Returns an empty map if no config has been saved yet (gateway uses its defaults).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getGlobalConfig() {
        return configRepository.findByConfigKeyAndNullTenant(GLOBAL_KEY)
                .map(GatewayConfig::getConfigValue)
                .orElse(Map.of());
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    /**
     * Persists the full gateway configuration and publishes a
     * {@code GatewayConfigChanged} event via the transactional outbox.
     *
     * <p>All running gateway instances will consume this event and reload their
     * in-memory config from the DB — making it rollout-safe.
     *
     * @param configJson the full GatewayConfigDto serialized as a Map
     * @param changedBy  actor who made the change (for audit)
     * @param section    human-readable section name for the event payload
     */
    @Transactional
    public Map<String, Object> saveGlobalConfig(Map<String, Object> configJson,
                                                 String changedBy,
                                                 String section) {
        GatewayConfig entity = configRepository
                .findByConfigKeyAndNullTenant(GLOBAL_KEY)
                .orElse(new GatewayConfig(GLOBAL_KEY, null, configJson, changedBy));

        entity.setConfigValue(configJson);
        entity.setUpdatedBy(changedBy);
        entity.incrementVersion();

        GatewayConfig saved = configRepository.save(entity);

        // Publish via transactional outbox — same TX as the save
        outboxStore.store(
                new DomainEvent.GatewayConfigChanged(
                        UUID.randomUUID(),
                        null,                // platform-wide
                        section,
                        changedBy,
                        Instant.now(),
                        null,
                        null),
                KafkaTopics.GATEWAY_CONFIG_EVENTS,
                null);

        log.info("Gateway config persisted to DB: section={} version={} by={}",
                section, saved.getVersion(), changedBy);

        return saved.getConfigValue();
    }

    /**
     * Converts a typed object (e.g. GatewayConfigDto) to a raw Map for storage.
     * Uses Jackson so all field names and types are preserved exactly.
     */
    public Map<String, Object> toMap(Object obj) {
        return objectMapper.convertValue(obj, new TypeReference<>() {});
    }

    /**
     * Converts the stored raw Map back to a typed object.
     */
    public <T> T fromMap(Map<String, Object> map, Class<T> type) {
        return objectMapper.convertValue(map, type);
    }
}

