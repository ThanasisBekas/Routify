package io.routify.gateway.routing;

import io.routify.gateway.config.GatewayConfigLoader;
import io.routify.gateway.certificate.CertificateRegistry;
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
 *   <li>{@code VAULT_CERT} — resolves the gateway TLS logicalId for vault-backed cert filters
 *       ({@code AUTH_CERT_VAULT}, {@code CERT_ROTATION}, {@code CERT_VAULT_EXPIRY_CHECK})</li>
 *   <li>{@code DOWNSTREAM_CREDENTIAL} — maps downstream credential fields</li>
 *   <li>{@code DOWNSTREAM_OAUTH2_PROVIDER} — maps downstream OAuth2 provider fields
 *       ({@code tokenUri}, {@code clientId}, {@code clientSecret}, {@code scope}) for
 *       the {@code DOWNSTREAM_BEARER_CC} filter's direct-config path (P-25)</li>
 *   <li>{@code MTLS_CLIENT_MAPPING} — resolves client-ID-to-certificate mappings from an
 *       {@code MTLS} auth provider for {@code AUTH_MTLS} filters</li>
 *   <li>{@code CLIENT_ID_MAPPING} — resolves client-ID header-value entries and org-ID
 *       mappings from a {@code CLIENT_ID} auth provider for {@code AUTH_CLIENT_ID} filters</li>
 *   <li>{@code TLS_SOURCE} — <strong>deprecated and removed</strong>: file-based certificate
 *       sources are no longer supported. Migrate to {@code VAULT_CERT} refs.</li>
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
            case "AUTH_PROVIDER"              -> resolveAuthProvider(refId, gwConfig);
            case "RATE_LIMIT_POLICY"          -> resolveRateLimitPolicy(refId, gwConfig);
            case "CIRCUIT_BREAKER_DEFAULTS"   -> resolveCircuitBreakerDefaults(gwConfig);
            case "RESILIENCE_DEFAULTS"        -> resolveResilienceDefaults(gwConfig);
            case "VAULT_CERT"                 -> resolveVaultCert(refId);
            case "DOWNSTREAM_CREDENTIAL"      -> resolveDownstreamCredential(refId, gwConfig);
            case "DOWNSTREAM_OAUTH2_PROVIDER" -> resolveDownstreamOauth2Provider(refId, gwConfig);
            case "MTLS_CLIENT_MAPPING"        -> resolveMtlsClientMapping(refId, gwConfig);
            case "CLIENT_ID_MAPPING"          -> resolveClientIdMapping(refId, gwConfig);
            case "TLS_SOURCE" -> {
                // TLS_SOURCE refs are no longer supported — file-based certificate sources
                // have been removed. All certificate management is handled by the Vault.
                // Return empty so the filter falls back to its own local config.
                log.warn("gatewayConfigRef refType 'TLS_SOURCE' is deprecated and has been removed. " +
                         "Migrate to VAULT_CERT refs backed by Certificate Vault groups.");
                yield Map.of();
            }
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
                    // introspection-specific fields (used by direct-config path)
                    putIfPresent(result, "parameterStyle", p.get("parameterStyle"));
                    putIfPresent(result, "parameterName",  p.get("parameterName"));
                    putIfPresent(result, "contentType",    p.get("contentType"));
                    if (p.containsKey("includeBasicClientAuthorization")) {
                        result.put("includeBasicClientAuthorization", p.get("includeBasicClientAuthorization"));
                    }
                }

                // Pass through provider name both as a diagnostics field and as the
                // config key that OAuth2TokenIntrospectGatewayFilterFactory reads,
                // so the filter can use the resolved name for logging/fallback.
                putIfPresent(result, "providerName", p.get("name"));
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


    /**
     * Resolves a {@code VAULT_CERT} ref for the three vault-backed cert filter factories:
     * {@code AUTH_CERT_VAULT}, {@code CERT_ROTATION}, and {@code CERT_VAULT_EXPIRY_CHECK}.
     *
     * <p>The {@code refId} is the vault certificate's {@code gatewayTlsLogicalId} — i.e. the
     * key under which it is registered in the {@link CertificateRegistry}.
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

    /**
     * Resolves a {@code DOWNSTREAM_OAUTH2_PROVIDER} ref for the
     * {@code DOWNSTREAM_BEARER_CC} filter factory.
     *
     * <p>Looks up a downstream OAuth2 provider entry by {@code refId} in the
     * {@code downstreamOauth2Providers} section of the gateway config and maps
     * its fields to the config keys expected by
     * {@link io.routify.gateway.filter.DownstreamOAuth2BearerGatewayFilterFactory.Config}:
     * <ul>
     *   <li>{@code tokenUri}  — the token endpoint URI</li>
     *   <li>{@code clientId}  — the OAuth2 client ID</li>
     *   <li>{@code clientSecret}  — the OAuth2 client secret</li>
     *   <li>{@code scope}  — space-separated scopes (optional)</li>
     *   <li>{@code includeBasicClientAuthorization} — send Basic header (optional)</li>
     * </ul>
     *
     * <p>When these fields are present in the resolved config, the filter factory
     * uses its direct-config path (P-25), bypassing the {@code oauth2ProviderName}
     * → YAML lookup.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveDownstreamOauth2Provider(String refId, Map<String, Object> gwConfig) {
        List<Object> providers = (List<Object>) gwConfig.get("downstreamOauth2Providers");
        if (providers == null) return Map.of();

        for (Object raw : providers) {
            Map<String, Object> p = asMap(raw);
            if (p == null) continue;
            if (refId.equals(str(p.get("id")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                putIfPresent(result, "tokenUri",     p.get("tokenUri"));
                putIfPresent(result, "clientId",     p.get("clientId"));
                putIfPresent(result, "clientSecret", p.get("clientSecret"));
                putIfPresent(result, "scope",        p.get("scope"));
                if (p.containsKey("includeBasicClientAuthorization")) {
                    result.put("includeBasicClientAuthorization",
                            p.get("includeBasicClientAuthorization"));
                }
                putIfPresent(result, "_providerName", p.get("name"));
                putIfPresent(result, "_providerType", "DOWNSTREAM_OAUTH2");
                log.debug("Resolved DOWNSTREAM_OAUTH2_PROVIDER '{}': tokenUri={}",
                        p.get("name"), p.get("tokenUri"));
                return result;
            }
        }
        return Map.of();
    }

    /**
     * Resolves a {@code MTLS_CLIENT_MAPPING} ref for the {@code AUTH_MTLS} filter factory.
     *
     * <p>Looks up an auth provider with {@code type: "MTLS"} matching {@code refId} in the
     * {@code authProviders} section of the gateway config, extracts its {@code clientMappings}
     * list, and returns them as a {@code values} list ready for
     * {@link io.routify.gateway.auth.properties.CertificateValuesConfig} binding.
     *
     * <p>Each mapping entry contains:
     * <ul>
     *   <li>{@code clientIdRequestHeader} — header carrying the client ID</li>
     *   <li>{@code clientIdValue} — expected client ID value</li>
     *   <li>{@code clientCertificateRequestHeader} — header carrying the PEM certificate</li>
     *   <li>{@code clientCertificateValue} — logicalId in the Certificate Registry</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMtlsClientMapping(String refId, Map<String, Object> gwConfig) {
        List<Object> providers = (List<Object>) gwConfig.get("authProviders");
        if (providers == null) return Map.of();

        for (Object raw : providers) {
            Map<String, Object> p = asMap(raw);
            if (p == null) continue;
            if (!"MTLS".equals(str(p.get("type")))) continue;
            if (!refId.equals(str(p.get("id")))) continue;

            Object rawMappings = p.get("clientMappings");
            if (!(rawMappings instanceof List<?> mappingsList)) {
                log.warn("MTLS auth provider '{}' has no clientMappings list — returning empty",
                        p.get("name"));
                return Map.of();
            }

            // Convert to the list-of-maps format expected by indexedValuesFilter → CertificateValuesConfig
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object entry : mappingsList) {
                Map<String, Object> m = asMap(entry);
                if (m == null) continue;
                Map<String, Object> mapping = new LinkedHashMap<>();
                putIfPresent(mapping, "clientIdRequestHeader",          m.get("clientIdRequestHeader"));
                putIfPresent(mapping, "clientIdValue",                  m.get("clientIdValue"));
                putIfPresent(mapping, "clientCertificateRequestHeader", m.get("clientCertificateRequestHeader"));
                putIfPresent(mapping, "clientCertificateValue",         m.get("clientCertificateValue"));
                values.add(mapping);
            }

            if (values.isEmpty()) {
                log.warn("MTLS auth provider '{}' has empty clientMappings — returning empty", p.get("name"));
                return Map.of();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("values", values);
            putIfPresent(result, "_providerName", p.get("name"));
            putIfPresent(result, "_providerType", "MTLS");
            log.debug("Resolved MTLS_CLIENT_MAPPING from provider '{}': {} mapping(s)",
                    p.get("name"), values.size());
            return result;
        }
        return Map.of();
    }

    /**
     * Resolves a {@code CLIENT_ID_MAPPING} ref for the {@code AUTH_CLIENT_ID} filter factory.
     *
     * <p>Looks up an auth provider with {@code type: "CLIENT_ID"} matching {@code refId} in the
     * {@code authProviders} section, extracts its {@code clientEntries} (name/value pairs)
     * and optional {@code clientIdMapping} (orgId→clientId map), and returns them in a
     * config map with keys {@code values} and {@code clientIdMapping}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveClientIdMapping(String refId, Map<String, Object> gwConfig) {
        List<Object> providers = (List<Object>) gwConfig.get("authProviders");
        if (providers == null) return Map.of();

        for (Object raw : providers) {
            Map<String, Object> p = asMap(raw);
            if (p == null) continue;
            if (!"CLIENT_ID".equals(str(p.get("type")))) continue;
            if (!refId.equals(str(p.get("id")))) continue;

            Object rawEntries = p.get("clientEntries");
            if (!(rawEntries instanceof List<?> entriesList)) {
                log.warn("CLIENT_ID auth provider '{}' has no clientEntries list — returning empty",
                        p.get("name"));
                return Map.of();
            }

            // Convert to the list-of-maps format expected by indexedValuesFilter → NameValuesConfig
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object entry : entriesList) {
                Map<String, Object> m = asMap(entry);
                if (m == null) continue;
                Map<String, Object> nameValue = new LinkedHashMap<>();
                putIfPresent(nameValue, "name",  m.get("name"));
                putIfPresent(nameValue, "value", m.get("value"));
                values.add(nameValue);
            }

            if (values.isEmpty()) {
                log.warn("CLIENT_ID auth provider '{}' has empty clientEntries — returning empty",
                        p.get("name"));
                return Map.of();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("values", values);

            // Include org→clientId mapping if present
            Object rawMapping = p.get("clientIdMapping");
            if (rawMapping instanceof Map<?, ?> mappingMap) {
                result.put("clientIdMapping", mappingMap);
            }

            putIfPresent(result, "_providerName", p.get("name"));
            putIfPresent(result, "_providerType", "CLIENT_ID");
            log.debug("Resolved CLIENT_ID_MAPPING from provider '{}': {} entry(ies)",
                    p.get("name"), values.size());
            return result;
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

