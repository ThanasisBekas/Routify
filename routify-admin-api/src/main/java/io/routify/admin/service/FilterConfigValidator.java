package io.routify.admin.service;

import io.routify.common.domain.FilterType;
import io.routify.common.exception.RoutifyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates the {@code config} map for each {@link FilterType} before the filter command
 * is published to Kafka. Prevents invalid/incomplete configs from being persisted to the
 * database and silently ignored (or defaulted) by the gateway at runtime.
 *
 * <p>Validation is intentionally conservative: only required fields and type checks are
 * enforced. Optional fields with sensible defaults in the gateway factory are not validated
 * here — they continue to fall through to the factory's default values.
 *
 * <p>This is the first line of defence. The gateway filter factories perform their own
 * validation at bind time (e.g. regex compilation, CIDR parsing), but by then the bad
 * config is already persisted and the filter silently disables itself.
 */
@Service
public class FilterConfigValidator {

    /**
     * Validates the config map for the given filter type.
     *
     * @param filterType       the validated, non-deprecated filter type
     * @param config           the user-supplied config map (may be null or empty)
     * @throws RoutifyException.Validation if required fields are missing or have wrong types
     */
    public void validate(FilterType filterType, Map<String, Object> config) {
        validate(filterType, config, null);
    }

    /**
     * Validates the config map for the given filter type, taking into account
     * an optional {@code gatewayConfigRef} that may supply credentials externally.
     *
     * @param filterType       the validated, non-deprecated filter type
     * @param config           the user-supplied config map (may be null or empty)
     * @param gatewayConfigRef optional ref to a gateway config entry (e.g. an auth provider)
     * @throws RoutifyException.Validation if required fields are missing or have wrong types
     */
    public void validate(FilterType filterType, Map<String, Object> config,
                         Map<String, Object> gatewayConfigRef) {
        if (config == null) {
            config = Map.of();
        }

        List<String> errors = new ArrayList<>();

        switch (filterType) {

            // ─── Authentication ─────────────────────────────────────────────────
            case AUTH_API_KEY -> {
                // headerName and queryParam both optional — at least one defaults
            }
            case AUTH_BASIC -> {
                // username/password can come from config OR from a gatewayConfigRef (auth provider).
                // If both are blank/missing in config, a gatewayConfigRef must be provided.
                boolean hasUsername = hasNonBlankString(config, "username");
                boolean hasPassword = hasNonBlankString(config, "password");
                if (!hasUsername && !hasPassword) {
                    boolean hasRef = gatewayConfigRef != null && !gatewayConfigRef.isEmpty();
                    if (!hasRef) {
                        errors.add("AUTH_BASIC requires either 'username' and 'password' in config, " +
                                   "or a gatewayConfigRef pointing to an auth provider");
                    }
                }
            }
            case AUTH_JWT -> {
                // issuer and audience are optional (JWT validation still works without them)
            }
            case AUTH_MTLS -> {
                // 'values' list is optional — can use gateway config ref instead
            }
            case AUTH_OAUTH2 -> {
                // providerName optional — uses default provider
            }
            case AUTH_CLIENT_ID -> {
                // 'values' list is optional — can use gateway config ref instead
            }
            case AUTH_CERT_VAULT -> {
                // logicalId is optional (blank = scan all registered IDs), but certificateHeader defaults
            }

            // ─── Downstream Auth Injection ──────────────────────────────────────
            case DOWNSTREAM_BASIC_AUTH -> {
                requireString(config, "username", errors);
                requireString(config, "password", errors);
            }
            case DOWNSTREAM_BEARER_CC -> {
                // oauth2ProviderName optional — uses default
            }
            case OAUTH2_TOKEN_RELAY -> {
                requireString(config, "tokenEndpoint", errors);
                requireString(config, "clientId", errors);
                requireString(config, "clientSecret", errors);
            }

            // ─── Rate Limiting ──────────────────────────────────────────────────
            case RATE_LIMIT_FIXED_WINDOW, RATE_LIMIT_SLIDING_WINDOW -> {
                requirePositiveNumber(config, "maxRequests", errors);
                requirePositiveNumber(config, "windowMs", errors);
            }

            // ─── Request / Response Modification ────────────────────────────────
            case REQUEST_HEADER_MODIFY, RESPONSE_HEADER_MODIFY -> {
                // add, set, remove are all optional maps — an empty filter is valid (no-op)
            }
            case RESPONSE_HEADER_REWRITE -> {
                requireString(config, "headerName", errors);
                requireString(config, "pattern", errors);
                requirePresent(config, "replacement", errors);
            }

            // ─── Body Transformation ────────────────────────────────────────────
            case BODY_JOLT_TRANSFORM -> {
                requireString(config, "spec", errors);
            }

            // ─── Validation ─────────────────────────────────────────────────────
            case VALIDATE_JSON_SCHEMA -> {
                requireString(config, "schema", errors);
            }
            case REQUEST_SIZE_LIMIT -> {
                requirePresent(config, "maxSize", errors);
            }
            case GRAPHQL_DEPTH_LIMIT -> {
                // all fields have sensible defaults
            }

            // ─── Performance ────────────────────────────────────────────────────
            case RESPONSE_CACHE -> {
                // all fields have sensible defaults
            }
            case REQUEST_DECOMPRESS -> {
                // all fields have sensible defaults
            }

            // ─── Reliability ────────────────────────────────────────────────────
            case IDEMPOTENCY_KEY -> {
                // all fields have sensible defaults
            }

            // ─── Resilience ─────────────────────────────────────────────────────
            case TIMEOUT -> {
                requirePositiveNumber(config, "timeoutMs", errors);
            }
            case CIRCUIT_BREAKER_V2 -> {
                // all fields have sensible defaults
            }
            case RETRY_V2 -> {
                // all fields have sensible defaults
            }

            // ─── Routing ────────────────────────────────────────────────────────
            case CONDITIONAL_ROUTE -> {
                requireString(config, "alternativeUri", errors);
            }
            case USER_ID_PAYLOAD_ROUTING -> {
                requireString(config, "alternativeUri", errors);
            }
            case GEO_ROUTE -> {
                // defaults available for all fields
            }

            // ─── Security ───────────────────────────────────────────────────────
            case IP_ACCESS_CONTROL -> {
                requireString(config, "addresses", errors);
            }

            // ─── Certificate / TLS ──────────────────────────────────────────────
            case CERT_ROTATION -> {
                requireString(config, "logicalId", errors);
            }
            case CERT_VAULT_EXPIRY_CHECK -> {
                requireString(config, "logicalId", errors);
            }

            // ─── Versioning ─────────────────────────────────────────────────────
            case API_VERSIONING -> {
                // defaults available for all fields
            }

            // ─── Observability ──────────────────────────────────────────────────
            case CORRELATION_ID, TENANT_CONTEXT, SECURITY_HEADERS -> {
                // no required config
            }
            case REQUEST_LOGGER -> {
                // all fields have sensible defaults
            }
            case CUSTOM_METRIC -> {
                requireString(config, "metricName", errors);
            }
            case BODY_SIZE_METRIC -> {
                // all fields have sensible defaults
            }

            // ─── Integration ────────────────────────────────────────────────────
            case WEBHOOK_NOTIFY -> {
                requireString(config, "webhookUrl", errors);
            }

            // ─── Developer Experience ───────────────────────────────────────────
            case MOCK_RESPONSE -> {
                // all fields have sensible defaults
            }

            // ─── Custom ─────────────────────────────────────────────────────────
            case CUSTOM_SPEL -> {
                requireString(config, "expression", errors);
            }

            // ─── AI ─────────────────────────────────────────────────────────────
            case AI_FILTER -> {
                requireString(config, "policyDescription", errors);
            }
            case AI_MODIFIER -> {
                requireString(config, "modificationPrompt", errors);
            }

            // Deprecated types should never reach here (blocked by resolveFilterType),
            // but handle gracefully if they do.
            default -> { /* no validation for unknown/deprecated types */ }
        }

        if (!errors.isEmpty()) {
            throw new RoutifyException.Validation(
                    "Invalid config for filter type %s: %s".formatted(
                            filterType.name(), String.join("; ", errors)));
        }
    }

    // ─── Validation helpers ────────────────────────────────────────────────────

    private boolean hasNonBlankString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof String s && !s.isBlank();
    }

    private void requirePresent(Map<String, Object> config, String key, List<String> errors) {
        if (!config.containsKey(key)) {
            errors.add("missing required field '%s'".formatted(key));
        }
    }

    private void requireString(Map<String, Object> config, String key, List<String> errors) {
        Object value = config.get(key);
        if (value == null) {
            errors.add("missing required field '%s'".formatted(key));
        } else if (value instanceof String s && s.isBlank()) {
            errors.add("field '%s' must not be blank".formatted(key));
        } else if (!(value instanceof String)) {
            errors.add("field '%s' must be a string, got %s".formatted(key, value.getClass().getSimpleName()));
        }
    }

    private void requirePositiveNumber(Map<String, Object> config, String key, List<String> errors) {
        Object value = config.get(key);
        if (value == null) {
            errors.add("missing required field '%s'".formatted(key));
            return;
        }
        if (value instanceof Number n) {
            if (n.doubleValue() <= 0) {
                errors.add("field '%s' must be a positive number, got %s".formatted(key, n));
            }
        } else if (value instanceof String s) {
            try {
                double d = Double.parseDouble(s);
                if (d <= 0) {
                    errors.add("field '%s' must be a positive number, got %s".formatted(key, s));
                }
            } catch (NumberFormatException e) {
                errors.add("field '%s' must be a number, got '%s'".formatted(key, s));
            }
        } else {
            errors.add("field '%s' must be a number, got %s".formatted(key, value.getClass().getSimpleName()));
        }
    }
}

