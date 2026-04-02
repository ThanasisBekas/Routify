package gr.routify.gateway.routing;

import gr.routify.gateway.config.GatewayConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Resolves a {@code gatewayConfigRef} on a filter snapshot to a concrete config map
 * that the filter factory can use.
 *
 * <h3>How it works</h3>
 * <p>When a filter definition is created with a gateway config reference (e.g. an
 * {@code AUTH_BASIC} filter linked to an Auth Provider named "my-basic-provider"),
 * the filter's local {@code config} map may be empty or contain only fallback defaults.
 * At route-build time, this resolver looks up the referenced entry in the currently
 * loaded {@link GatewayConfigLoader#getConfig() gateway config} and merges it into
 * the filter config map.
 *
 * <h3>Merge strategy — local overrides ref</h3>
 * <ol>
 *   <li>Start with the values from the referenced gateway config entry (authoritative source)</li>
 *   <li>Overlay any non-null, non-blank values from the filter's local {@code config}
 *       (allows per-filter overrides of individual fields)</li>
 * </ol>
 *
 * <h3>Supported refTypes and their config mappings</h3>
 * <ul>
 *   <li>{@code AUTH_PROVIDER} — maps auth provider fields to filter config keys
 *       (e.g. {@code username}, {@code password}, {@code jwksUri}, {@code issuer}, etc.)</li>
 *   <li>{@code RATE_LIMIT_POLICY} — maps policy fields (replenishRate, burstCapacity, etc.)</li>
 *   <li>{@code CIRCUIT_BREAKER_DEFAULTS} — maps circuit breaker defaults</li>
 *   <li>{@code RESILIENCE_DEFAULTS} — maps retry/timeout defaults</li>
 *   <li>{@code TLS_SOURCE} — maps certificate path and watch settings</li>
 *   <li>{@code VAULT_CERT} — resolves the gateway TLS logicalId for vault-backed cert filters
 *       ({@code AUTH_CERT_VAULT}, {@code CERT_ROTATION}, {@code CERT_VAULT_EXPIRY_CHECK})</li>
 *   <li>{@code DOWNSTREAM_CREDENTIAL} — maps downstream credential fields</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayConfigRefResolver {

    private final GatewayConfigLoader configLoader;

    /**
     * Resolves the {@code gatewayConfigRef} and returns a merged config map ready
     * for use by filter factories.
     *
     * <p>If the ref is null or cannot be resolved, the original {@code localConfig}
     * is returned unchanged.
     *
     * @param localConfig      the filter's own config (may be empty)
     * @param gatewayConfigRef the ref map, e.g. {@code {refType, refId, refName}}
     * @return merged config with gateway ref values as base + local config overrides
     */
    public Map<String, Object> resolve(Map<String, Object> localConfig,
                                       Map<String, Object> gatewayConfigRef) {
        if (gatewayConfigRef == null || gatewayConfigRef.isEmpty()) {
            return localConfig != null ? localConfig : Map.of();
        }

        String refType = str(gatewayConfigRef.get("refType"));
        String refId   = str(gatewayConfigRef.get("refId"));
        String refName = str(gatewayConfigRef.get("refName"));

        if (refType == null || refId == null) {
            log.warn("gatewayConfigRef has null refType or refId — using local config only");
            return localConfig != null ? localConfig : Map.of();
        }

        Map<String, Object> gwConfig = configLoader.getConfig();
        if (gwConfig == null || gwConfig.isEmpty()) {
            log.warn("Gateway config not yet loaded — cannot resolve ref {}:{} ({})",
                    refType, refId, refName);
            return localConfig != null ? localConfig : Map.of();
        }

        Map<String, Object> refConfig = switch (refType) {
            case "AUTH_PROVIDER"           -> resolveAuthProvider(refId, gwConfig);
            case "RATE_LIMIT_POLICY"       -> resolveRateLimitPolicy(refId, gwConfig);
            case "CIRCUIT_BREAKER_DEFAULTS"-> resolveCircuitBreakerDefaults(gwConfig);
            case "RESILIENCE_DEFAULTS"     -> resolveResilienceDefaults(gwConfig);
            case "TLS_SOURCE"              -> resolveTlsSource(refId, gwConfig);
            case "VAULT_CERT"              -> resolveVaultCert(refId);
            case "DOWNSTREAM_CREDENTIAL"   -> resolveDownstreamCredential(refId, gwConfig);
            default -> {
                log.warn("Unknown gatewayConfigRef refType '{}' — skipping resolution", refType);
                yield Map.of();
            }
        };

        if (refConfig.isEmpty()) {
            log.warn("Could not find gateway config entry for refType={} refId={} ({})",
                    refType, refId, refName);
            return localConfig != null ? localConfig : Map.of();
        }

        // Merge: ref values are the base; local config non-blank values override
        Map<String, Object> merged = new LinkedHashMap<>(refConfig);
        if (localConfig != null) {
            localConfig.forEach((k, v) -> {
                if (v != null && !v.toString().isBlank()) {
                    merged.put(k, v);
                }
            });
        }

        log.debug("Resolved gatewayConfigRef refType={} refId={} ({}) → {} keys merged",
                refType, refId, refName, merged.size());
        return merged;
    }

    // ─── Per-refType resolvers ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveAuthProvider(String refId, Map<String, Object> gwConfig) {
        List<Object> providers = (List<Object>) gwConfig.get("authProviders");
        if (providers == null) return Map.of();

        for (Object raw : providers) {
            Map<String, Object> p = asMap(raw);
            if (p == null) continue;
            if (refId.equals(str(p.get("id")))) {
                // Map auth provider fields → filter config keys
                Map<String, Object> result = new LinkedHashMap<>();
                String type = str(p.get("type"));

                if ("BASIC".equals(type)) {
                    putIfPresent(result, "username", p.get("username"));
                    putIfPresent(result, "password", p.get("password"));
                    putIfPresent(result, "realm",    p.get("realm"));
                } else if ("JWT_VERIFY".equals(type)) {
                    putIfPresent(result, "jwksUri",   p.get("jwksUri"));
                    putIfPresent(result, "issuerUri",  p.get("issuer"));
                    putIfPresent(result, "audience",   p.get("audience"));
                    putIfPresent(result, "algorithm",  p.get("algorithm"));
                } else if (type != null && type.startsWith("OAUTH2")) {
                    putIfPresent(result, "introspectUri", p.get("uri"));
                    putIfPresent(result, "clientId",     p.get("clientId"));
                    putIfPresent(result, "clientSecret", p.get("clientSecret"));
                    putIfPresent(result, "scope",        p.get("scope"));
                    // password grant
                    putIfPresent(result, "username",     p.get("username"));
                    putIfPresent(result, "password",     p.get("password"));
                }

                // Always pass through enabled and name for diagnostics
                putIfPresent(result, "_providerName", p.get("name"));
                putIfPresent(result, "_providerType", type);
                return result;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRateLimitPolicy(String refId, Map<String, Object> gwConfig) {
        List<Object> policies = (List<Object>) gwConfig.get("rateLimitPolicies");
        if (policies == null) return Map.of();

        for (Object raw : policies) {
            Map<String, Object> p = asMap(raw);
            if (p == null) continue;
            if (refId.equals(str(p.get("id")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                putIfPresent(result, "replenishRate",    p.get("replenishRate"));
                putIfPresent(result, "burstCapacity",    p.get("burstCapacity"));
                putIfPresent(result, "requestedTokens",  p.get("requestedTokens"));
                putIfPresent(result, "windowMs",         p.get("windowMs"));
                putIfPresent(result, "keyResolver",      p.get("keyResolver"));
                putIfPresent(result, "algorithm",        p.get("algorithm"));
                return result;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveCircuitBreakerDefaults(Map<String, Object> gwConfig) {
        Map<String, Object> cb = asMap(gwConfig.get("circuitBreakerDefaults"));
        if (cb == null) return Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "slidingWindowSize",                      cb.get("slidingWindowSize"));
        putIfPresent(result, "failureRateThreshold",                   cb.get("failureRateThreshold"));
        putIfPresent(result, "waitDurationInOpenState",                cb.get("waitDurationInOpenState"));
        putIfPresent(result, "permittedCallsInHalfOpen",
                cb.get("permittedNumberOfCallsInHalfOpenState"));
        putIfPresent(result, "fallbackUri",                            cb.get("fallbackUri"));
        putIfPresent(result, "recordExceptions",                       cb.get("recordExceptions"));
        putIfPresent(result, "slowCallRateThreshold",                  cb.get("slowCallRateThreshold"));
        putIfPresent(result, "slowCallDurationMs",                     cb.get("slowCallDurationThresholdMs"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveResilienceDefaults(Map<String, Object> gwConfig) {
        Map<String, Object> rd = asMap(gwConfig.get("resilienceDefaults"));
        if (rd == null) return Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "retries",            rd.get("retryMaxAttempts"));
        putIfPresent(result, "waitDuration",        rd.get("retryWaitDuration"));
        putIfPresent(result, "exponentialBackoff",  rd.get("retryExponentialBackoff"));
        putIfPresent(result, "multiplier",          rd.get("retryExponentialMultiplier"));
        putIfPresent(result, "maxWaitDuration",     rd.get("retryMaxWaitDuration"));
        putIfPresent(result, "timeoutMs",           rd.get("timeoutDuration")); // convert later if needed
        putIfPresent(result, "cancelRunningFuture", rd.get("timeoutCancelRunningFuture"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTlsSource(String refId, Map<String, Object> gwConfig) {
        Map<String, Object> tls = asMap(gwConfig.get("tlsConfig"));
        if (tls == null) return Map.of();

        List<Object> sources = (List<Object>) tls.get("fileSources");
        if (sources == null) return Map.of();

        for (Object raw : sources) {
            Map<String, Object> src = asMap(raw);
            if (src == null) continue;
            if (refId.equals(str(src.get("logicalId")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                // logicalId is the primary key used by CertificateRegistry and filter factories
                putIfPresent(result, "logicalId",          src.get("logicalId"));
                putIfPresent(result, "certificatePath",    src.get("certificatePath"));
                putIfPresent(result, "privateKeyPath",     src.get("privateKeyPath"));
                putIfPresent(result, "privateKeyPassword", src.get("privateKeyPassword"));
                putIfPresent(result, "watchForChanges",    src.get("watchForChanges"));
                putIfPresent(result, "expiryWarning",      tls.get("expiryWarning"));
                putIfPresent(result, "fileWatchInterval",  tls.get("fileWatchInterval"));
                return result;
            }
        }
        return Map.of();
    }

    /**
     * Resolves a {@code VAULT_CERT} ref for the three vault-backed cert filter factories:
     * {@code AUTH_CERT_VAULT}, {@code CERT_ROTATION}, and {@code CERT_VAULT_EXPIRY_CHECK}.
     *
     * <p>The {@code refId} is the vault certificate's {@code gatewayTlsLogicalId} — i.e. the
     * key under which it is registered in the {@link gr.routify.gateway.certificate.CertificateRegistry}.
     * Injecting it as {@code logicalId} lets the filter factories find the cert in the registry
     * at request time without the operator having to repeat the logical ID in the filter config.
     */
    private Map<String, Object> resolveVaultCert(String refId) {
        if (refId == null || refId.isBlank()) {
            log.warn("VAULT_CERT ref has blank refId — cannot inject logicalId");
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logicalId", refId);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveDownstreamCredential(String refId, Map<String, Object> gwConfig) {
        List<Object> creds = (List<Object>) gwConfig.get("downstreamCredentials");
        if (creds == null) return Map.of();

        for (Object raw : creds) {
            Map<String, Object> c = asMap(raw);
            if (c == null) continue;
            if (refId.equals(str(c.get("id")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                String type = str(c.get("type"));
                putIfPresent(result, "credentialType", type);
                putIfPresent(result, "username",       c.get("username"));
                putIfPresent(result, "password",       c.get("password"));
                putIfPresent(result, "headerName",     c.get("headerName"));
                putIfPresent(result, "headerValue",    c.get("headerValue"));
                return result;
            }
        }
        return Map.of();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            try {
                return (Map<String, Object>) m;
            } catch (ClassCastException e) {
                return null;
            }
        }
        return null;
    }
}

