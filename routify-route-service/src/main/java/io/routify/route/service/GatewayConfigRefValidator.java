package io.routify.route.service;

import io.routify.common.exception.RoutifyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates {@code gatewayConfigRef} references on filter create/update.
 *
 * <h3>Validation strategy (per risk register P-14)</h3>
 * <ul>
 *   <li><strong>Unknown refType → reject</strong> ({@link RoutifyException.Validation}):
 *       an unknown type is always a programming error and would silently fail at
 *       gateway route-build time.</li>
 *   <li><strong>Missing refId on list-based types → reject</strong>: a blank refId
 *       is structurally invalid and cannot be resolved.</li>
 *   <li><strong>Referenced entry not found → warn</strong>: the entry may be created
 *       later (eventual consistency). A warning is logged so operators can investigate,
 *       but the save is not blocked.</li>
 * </ul>
 *
 * @see io.routify.common.event.CommandEvent.CreateFilter
 * @see io.routify.common.event.CommandEvent.UpdateFilter
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayConfigRefValidator {

    /**
     * All refTypes recognised by the gateway's {@code GatewayConfigRefResolver}.
     * Any value not in this set is rejected eagerly.
     */
    private static final Set<String> KNOWN_REF_TYPES = Set.of(
            "AUTH_PROVIDER",
            "RATE_LIMIT_POLICY",
            "CIRCUIT_BREAKER_DEFAULTS",
            "RESILIENCE_DEFAULTS",
            "VAULT_CERT",
            "DOWNSTREAM_CREDENTIAL",
            "MTLS_CLIENT_MAPPING",
            "CLIENT_ID_MAPPING"
    );

    /**
     * RefTypes that resolve against a list in the gateway config and require a
     * non-blank {@code refId} that matches an entry by its {@code id} field.
     */
    private static final Map<String, String> LIST_REF_TYPE_TO_CONFIG_KEY = Map.of(
            "AUTH_PROVIDER",         "authProviders",
            "RATE_LIMIT_POLICY",     "rateLimitPolicies",
            "DOWNSTREAM_CREDENTIAL", "downstreamCredentials",
            "MTLS_CLIENT_MAPPING",   "authProviders",
            "CLIENT_ID_MAPPING",     "authProviders"
    );

    /**
     * RefTypes that resolve against a singleton section in the gateway config.
     * No {@code refId} match is needed — the section just needs to exist.
     */
    private static final Map<String, String> SINGLETON_REF_TYPE_TO_CONFIG_KEY = Map.of(
            "CIRCUIT_BREAKER_DEFAULTS", "circuitBreakerDefaults",
            "RESILIENCE_DEFAULTS",      "resilienceDefaults"
    );

    private final GatewayConfigService configService;

    /**
     * Validates a {@code gatewayConfigRef} before persisting a filter.
     *
     * @param gatewayConfigRef the ref map from the command ({@code refType}, {@code refId}, {@code refName})
     * @throws RoutifyException.Validation if the ref structure is invalid (unknown type or blank refId)
     */
    public void validate(Map<String, Object> gatewayConfigRef) {
        if (gatewayConfigRef == null || gatewayConfigRef.isEmpty()) {
            return; // no ref — nothing to validate
        }

        String refType = str(gatewayConfigRef.get("refType"));
        String refId   = str(gatewayConfigRef.get("refId"));

        // 1. refType must be present and known
        if (refType == null || refType.isBlank()) {
            throw new RoutifyException.Validation(
                    "gatewayConfigRef is present but missing required 'refType' field.");
        }

        if (!KNOWN_REF_TYPES.contains(refType)) {
            throw new RoutifyException.Validation(
                    "Unknown gatewayConfigRef refType: '%s'. Known types: %s"
                            .formatted(refType, KNOWN_REF_TYPES));
        }

        // 2. VAULT_CERT — refId is the logicalId, no gateway config lookup needed
        if ("VAULT_CERT".equals(refType)) {
            if (refId == null || refId.isBlank()) {
                throw new RoutifyException.Validation(
                        "VAULT_CERT gatewayConfigRef requires a non-blank 'refId' (the certificate logical ID).");
            }
            return; // valid — cert existence is verified at gateway request time
        }

        // 3. List-based types — require non-blank refId
        if (LIST_REF_TYPE_TO_CONFIG_KEY.containsKey(refType)) {
            if (refId == null || refId.isBlank()) {
                throw new RoutifyException.Validation(
                        "%s gatewayConfigRef requires a non-blank 'refId'.".formatted(refType));
            }
            warnIfEntryNotFound(refType, refId, LIST_REF_TYPE_TO_CONFIG_KEY.get(refType));
            return;
        }

        // 4. Singleton types — section must exist (warning only if missing)
        if (SINGLETON_REF_TYPE_TO_CONFIG_KEY.containsKey(refType)) {
            warnIfSectionMissing(refType, SINGLETON_REF_TYPE_TO_CONFIG_KEY.get(refType));
        }
    }

    // ─── Warning-only checks (don't block the save) ──────────────────────────

    /**
     * Checks if a list-based config section contains an entry with the given refId.
     * Logs a warning if not found but does <em>not</em> throw — the entry may be
     * created later (eventual consistency).
     */
    private void warnIfEntryNotFound(String refType, String refId, String configKey) {
        Map<String, Object> config = configService.getGlobalConfig();
        if (config.isEmpty()) {
            log.warn("gatewayConfigRef validation: no gateway config saved yet. " +
                     "{} ref with refId='{}' cannot be verified — it may resolve after config is saved.",
                    refType, refId);
            return;
        }

        Object section = config.get(configKey);
        if (!(section instanceof List<?> list)) {
            log.warn("gatewayConfigRef validation: gateway config section '{}' not found or not a list. " +
                     "{} ref with refId='{}' cannot be verified.",
                    configKey, refType, refId);
            return;
        }

        boolean found = false;
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                if (refId.equals(str(map.get("id")))) {
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            log.warn("gatewayConfigRef validation: no {} with id='{}' found in gateway config section '{}'. " +
                     "The filter will be saved but the ref may not resolve at gateway route-build time.",
                    refType, refId, configKey);
        }
    }

    /**
     * Checks if a singleton config section exists.
     * Logs a warning if missing but does <em>not</em> throw.
     */
    private void warnIfSectionMissing(String refType, String configKey) {
        Map<String, Object> config = configService.getGlobalConfig();
        if (config.isEmpty() || config.get(configKey) == null) {
            log.warn("gatewayConfigRef validation: gateway config section '{}' not found. " +
                     "{} ref may not resolve at gateway route-build time.",
                    configKey, refType);
        }
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }
}

