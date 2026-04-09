# Routify — Route Filter Roadmap

## Executive Summary

A thorough audit of all 29 active filter types across 5 layers (enum → gateway factory → RouteDefinitionBuilder → admin-api → dashboard) identified **22 issues** grouped into **7 initiatives**. Issues range from critical (silent filter failures, missing input validation) to moderate (test coverage gaps, blocking code in the reactive pipeline, incomplete deprecated-filter retirement across all layers). This roadmap prioritises safety-critical fixes first, then correctness, then hardening and cleanup.

---

## Audit Methodology

Every active `FilterType` enum value was traced through:
1. **`FilterType.java`** (routify-common) — 29 active + 12 deprecated enum values
2. **`*GatewayFilterFactory.java`** (routify-api-gateway) — 33 factory classes in `filter/` + subdirectories
3. **`RouteDefinitionBuilder.java`** (routify-api-gateway) — `switch` expression mapping `filterType` strings → SCG filter names
4. **`CreateFilterRequest.java`** / `RouteFilterMessagingClient.java` (routify-admin-api) — BFF request validation + command publishing
5. **`filterRegistry.ts`** / `filterConfigConstants.ts`** / `FilterType` TS union (routify-dashboard) — UI metadata + default configs
6. **Test files** — 44 test classes in `routify-api-gateway/src/test/`

### Cross-Reference Matrix (29 Active Filters)

| FilterType | Gateway Factory | Builder Case | TS Union | Registry | Default Config | Tests |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| AUTH_API_KEY | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AUTH_BASIC | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AUTH_JWT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AUTH_MTLS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AUTH_OAUTH2 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AUTH_CLIENT_ID | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AUTH_CERT_VAULT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| DOWNSTREAM_BASIC_AUTH | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| DOWNSTREAM_BEARER_CC | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| OAUTH2_TOKEN_RELAY | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RATE_LIMIT_FIXED_WINDOW | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RATE_LIMIT_SLIDING_WINDOW | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| REQUEST_HEADER_MODIFY | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RESPONSE_HEADER_MODIFY | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RESPONSE_HEADER_REWRITE | ✅ ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ |
| BODY_JOLT_TRANSFORM | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| VALIDATE_JSON_SCHEMA | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| REQUEST_SIZE_LIMIT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GRAPHQL_DEPTH_LIMIT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RESPONSE_CACHE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| REQUEST_DECOMPRESS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| IDEMPOTENCY_KEY | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| TIMEOUT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CIRCUIT_BREAKER_V2 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RETRY_V2 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CONDITIONAL_ROUTE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| USER_ID_PAYLOAD_ROUTING | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GEO_ROUTE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| IP_ACCESS_CONTROL | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CERT_ROTATION | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CERT_VAULT_EXPIRY_CHECK | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| API_VERSIONING | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CORRELATION_ID | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| REQUEST_LOGGER | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| TENANT_CONTEXT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| SECURITY_HEADERS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CUSTOM_METRIC | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| BODY_SIZE_METRIC | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| WEBHOOK_NOTIFY | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| MOCK_RESPONSE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CUSTOM_SPEL | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AI_FILTER | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AI_MODIFIER | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## Initiative 1: Input Validation — Prevent Invalid Filters from Being Persisted ✅ COMPLETED

**Priority: 🔴 Critical** | **Effort: S** | **Risk: High — garbage data in DB causes silent gateway failures**

**Status: ✅ Completed** — All issues resolved. Changes:
- `CreateFilterRequest.filterType` now has `@NotBlank` validation (Issue 1.1)
- `RouteFilterMessagingClient.resolveFilterType()` wraps `FilterType.valueOf()` with `RoutifyException.Validation` for unknown types, and rejects deprecated types (Issue 1.1)
- `FilterType.DEPRECATED` set and `isDeprecated()` method added to `routify-common` (foundation for Initiative 6)
- `FilterConfigValidator` service validates required config fields per filter type before Kafka command is published (Issue 1.2)
- Unit tests: `FilterTypeTest` extended with DEPRECATED set tests; `FilterConfigValidatorTest` added with full coverage

### Issue 1.1: `CreateFilterRequest.filterType` has no validation

**Location:** `routify-admin-api/src/main/java/io/routify/admin/dto/CreateFilterRequest.java`

The `filterType` field is a bare `String` with no `@NotBlank` constraint and no enum validation. Any garbage string (e.g., `"FOOBAR"`) is accepted by the admin-api, persisted to the database, and silently ignored by the gateway when `RouteDefinitionBuilder` hits the `default -> null` branch.

**Impact:** Users can create filter definitions with invalid types that appear in the UI as created but silently do nothing when attached to a route.

**Fix:**
- Add `@NotBlank` to `filterType` in `CreateFilterRequest`.
- In `RouteFilterMessagingClient.sendCreateFilter()`, the `FilterType.valueOf()` call at line 259 already throws `IllegalArgumentException` for invalid values — but this throws a raw exception, not a `RoutifyException.Validation`. Wrap it with proper error handling.
- Optionally: change `filterType` to be of type `FilterType` (the Java enum) directly, so Jackson validation catches invalid values at deserialization time.

### Issue 1.2: No per-filter-type config schema validation exists at any layer

**Location:** All layers (admin-api, route-service, gateway)

The filter `config` is an opaque `Map<String, Object>` stored as JSONB. Invalid config keys/values (e.g., `maxRequests: "banana"` on a rate limiter, or missing required fields like `logicalId` on `CERT_ROTATION`) are only caught at runtime when the gateway's property binder fails or the filter factory falls back to defaults.

**Impact:** Filters silently use default config values when user-supplied values have wrong types or keys. For example, a rate limiter configured with `maxRequests: "banana"` silently defaults to 100.

**Fix:**
- Add a `FilterConfigValidator` service in `routify-admin-api` that validates the config map against the expected schema for each `FilterType` before publishing the Kafka command.
- At minimum, validate required fields (e.g., `CERT_ROTATION` requires `logicalId`, `RESPONSE_HEADER_REWRITE` requires `headerName` + `pattern` + `replacement`).
- Return `RoutifyException.Validation` with a clear error message listing invalid/missing fields.

---

## Initiative 2: Reactive Safety — Remove Blocking Code from the Gateway Module ✅ COMPLETED

**Priority: 🔴 Critical** | **Effort: M** | **Risk: High — thread starvation under load**

**Status: ✅ Completed** — All issues resolved. Changes:
- `ResponseHeaderRewriteGatewayFilterFactory`: removed `CompletableFuture.supplyAsync().get()` blocking call — replaced with inline `matcher.replaceAll()`/`matcher.replaceFirst()` since pathological regex patterns are already rejected at config time by `validatePatternSafety()` (Issue 2.1)
- `ResponseHeaderRewriteGatewayFilterFactory`: replaced `.then(Mono.fromRunnable(...))` with `exchange.getResponse().beforeCommit()` to ensure header rewrites are applied before the response is committed to the wire (Issue 2.2)
- `ResponseHeaderModifyGatewayFilterFactory`: replaced `.then(Mono.fromRunnable(...))` with `exchange.getResponse().beforeCommit()` to ensure header modifications (set/add/remove) are applied before the response is committed (Issue 2.2)
- Removed `matchTimeoutMs` config field from `ResponseHeaderRewriteGatewayFilterFactory.Config` (no longer needed without the `CompletableFuture` timeout)
- Updated `ResponseHeaderRewriteTest` and `HeaderFilterFactoriesTest` — all test chains now use `exchange.getResponse().setComplete()` to trigger `beforeCommit` callbacks; all 27 tests pass

### Issue 2.1: `ResponseHeaderRewriteGatewayFilterFactory` uses `CompletableFuture.supplyAsync()` + `.get()` with a blocking wait

**Location:** `routify-api-gateway/.../filter/ResponseHeaderRewriteGatewayFilterFactory.java`, lines 155–160

```java
Future<String> future = CompletableFuture.supplyAsync(() -> {
    Matcher matcher = pattern.matcher(value);
    return replaceAll ? matcher.replaceAll(replacement) : matcher.replaceFirst(replacement);
});
return future.get(matchTimeoutMs, TimeUnit.MILLISECONDS);  // BLOCKS the calling thread
```

This code runs inside `Mono.fromRunnable()` from the `.then()` operator, which executes on the Netty event loop. The `.get()` call parks the Netty I/O thread for up to 100ms per header value. Under high concurrency this can cause thread starvation and cascading latency.

**Impact:** Under load, response header rewriting can block all Netty I/O threads, causing the entire gateway to stall.

**Fix:**
- Replace `CompletableFuture.supplyAsync().get()` with a `Mono.fromCallable()` offloaded to `Schedulers.boundedElastic()`.
- Alternatively, since the nested quantifier pattern is already rejected at config time, the timeout protection may be unnecessary — a simple `matcher.replaceAll()` / `matcher.replaceFirst()` call inline is likely safe enough.
- If the timeout protection is desired, use `Mono.timeout()` instead of blocking `.get()`.

### Issue 2.2: `ResponseHeaderModifyGatewayFilterFactory` and `ResponseHeaderRewriteGatewayFilterFactory` use `.then(Mono.fromRunnable(...))` to modify response headers

**Location:** `ResponseHeaderModifyGatewayFilterFactory.java` line 38, `ResponseHeaderRewriteGatewayFilterFactory.java` line 105

```java
return chain.filter(exchange).then(Mono.fromRunnable(() -> {
    response.getHeaders().set(header, value);  // Too late — response may be committed
}));
```

The `.then(Mono.fromRunnable(...))` runs **after** the response has been fully written. By this time, headers have already been sent to the client — any mutations are silently discarded. This affects:
- `RESPONSE_HEADER_MODIFY` — `set`, `add`, `remove` operations on response headers
- `RESPONSE_HEADER_REWRITE` — regex-based response header rewriting

**Impact:** Response header modifications are silently dropped for streamed/chunked responses where headers are committed before the body completes. For small responses that are buffered, it may work by accident.

**Fix:**
- Use `exchange.getResponse().beforeCommit(() -> { ... return Mono.empty(); })` to inject header changes before the response is committed, OR
- Use a `ServerHttpResponseDecorator` pattern (wrap the response and apply header changes in the `writeWith()` method before delegating), OR
- Apply header modifications via `exchange.mutate().response(decoratedResponse).build()` before calling `chain.filter()`.

---

## Initiative 3: Type Safety — RouteDefinitionBuilder Switch Expression ✅ COMPLETED

**Priority: 🟠 High** | **Effort: S** | **Risk: Medium — silent filter breakage on enum rename**

**Status: ✅ Completed** — All issues resolved. Changes:
- `RouteDefinitionBuilder.buildFilterDefinition()` switch converted from 40+ string literal cases to `FilterType` enum values — any enum rename now causes a compile error instead of silent runtime failure (Issue 3.1)
- `FilterType.valueOf()` parse at the switch site with `IllegalArgumentException`/`NullPointerException` catch for unknown filter type strings — graceful null return with warning log
- `chainHasStripPrefix` check updated to use `FilterType.PATH_STRIP_PREFIX.name()` instead of string literal
- `RouteSnapshotDto.FilterSnapshotDto.filterType()` remains `String` (wire format DTO) — enum parsing happens at the switch site only
- Created `RouteDefinitionBuilderTest.java` with 70+ test methods covering: parameterized tests for all 41 FilterType enum values, SCG filter name mapping verification, config flattening (lists/maps/scalars/nulls), `indexedValuesFilter` expansion (AUTH_MTLS, AUTH_CLIENT_ID), AI filter metadata injection, named filters (zero-config), deprecated filter behaviour (7 functional + 5 null), unknown/null/empty filter type handling, full `build()` predicates/metadata/ordering, and exhaustiveness verification (Issue 3.2)

### Issue 3.1: `RouteDefinitionBuilder.buildFilterDefinition()` switch uses string literals instead of `FilterType` enum values

**Location:** `routify-api-gateway/.../routing/RouteDefinitionBuilder.java`, lines 212–392

```java
return switch (filter.filterType()) {
    case "AUTH_JWT" -> customFilter("JwtAuth", cfg);
    case "AUTH_API_KEY" -> customFilter("ApiKeyAuth", cfg);
    // ... 40+ more string literal cases
    default -> {
        log.warn("Unknown filter type '{}' — skipping", filter.filterType());
        yield null;
    }
};
```

The `filterType()` on `RouteSnapshotDto.FilterSnapshotDto` is a `String`, so the switch uses string literals. If a `FilterType` enum value is renamed in `routify-common`, the code compiles fine but the gateway silently ignores all filters of that type.

**Impact:** Any rename or typo in the `FilterType` enum breaks the mapping with zero compile-time errors.

**Fix:**
- Change `RouteSnapshotDto.FilterSnapshotDto.filterType()` to return `FilterType` (the Java enum) instead of `String`.
- Update the switch expression to pattern-match on enum values:
  ```java
  return switch (FilterType.valueOf(filter.filterType())) {
      case AUTH_JWT -> customFilter("JwtAuth", cfg);
      // ...
  };
  ```
- Alternatively, add an exhaustiveness check: verify at startup that every non-deprecated `FilterType` value has a matching case in the switch.

### Issue 3.2: No unit test for `RouteDefinitionBuilder` — the filter-type→factory mapping is untested

**Location:** Missing file — no `RouteDefinitionBuilderTest.java` exists

The 40+ case branches in the switch expression have zero test coverage. Only `CustomFilterListSerializationTest` exists in the `routing/` package, which tests serialization but not the filter-type mapping.

**Impact:** Regressions in the switch expression (e.g., swapped filter names, missing new filter types, broken config flattening) are only caught at runtime.

**Fix:**
- Create `RouteDefinitionBuilderTest.java` with parameterized tests that verify:
  1. Every non-deprecated `FilterType` produces a non-null `FilterDefinition`
  2. The filter name in the produced `FilterDefinition` matches the expected SCG filter factory name
  3. Config values are correctly flattened (lists → comma-separated, maps → dotted keys)
  4. `indexedValuesFilter` correctly expands `values` lists for `AUTH_MTLS` and `AUTH_CLIENT_ID`

---

## Initiative 4: Test Coverage — Fill Critical Gaps ✅ COMPLETED

**Priority: 🟡 Medium** | **Effort: M** | **Risk: Medium — undetected regressions**

**Status: ✅ Completed** — All issues resolved. Changes:
- Created `TestCertificateHelper.java` — shared test utility for generating self-signed X.509 certificates with BouncyCastle (configurable CN, validity window, PEM encoding)
- Created `CertVaultAuthGatewayFilterFactoryTest.java` — 14 tests covering: missing/blank certificate header with requireCertificate flag, invalid PEM, scoped lookup (active match with 9 identity headers, known-but-inactive, unknown cert), global fingerprint scan (active match, no match), stripCertificateHeader, custom header name, filter order (Issue 4.1)
- Created `CertRotationGatewayFilterFactoryTest.java` — 10 tests covering: blank/null logicalId pass-through, missing/blank certificate header, invalid PEM, active match with X-Cert-Version/X-Cert-Fingerprint headers, inactive match (rotation required), unknown cert, custom header name (Issue 4.2)
- Created `CertVaultExpiryCheckGatewayFilterFactoryTest.java` — 9 tests covering: blank logicalId pass-through, no active cert (503), all expired (503), expiring soon + reject (503), expiring soon + pass (EXPIRING_SOON headers), active cert (ACTIVE headers), injectMetadataHeaders=false, multiple versions picks latest expiry (Issue 4.3)
- Created `TenantContextGatewayFilterFactoryTest.java` — 13 tests covering: caller-provided mode (JWT tenant, caller header, matching JWT+caller, mismatch → 403, no tenant), auto-inject mode (route metadata injection, JWT priority over metadata, no tenant), quota enforcement (ENTERPRISE skip, within quota, exceeded → 429, Redis error fail-open, non-UUID skip), gateway config edge cases (null/empty config defaults), filter order (+100) (Issue 4.4)
- Created `MtlsAuthGatewayFilterFactoryTest.java` — 8 tests covering: single mapping happy path with CN header injection, multi-mapping iteration (second mapping match, no match), rejection paths (cert mismatch, missing cert header, missing clientId header, invalid PEM), CN extraction, filter order (Issue 4.5)

### Issue 4.1: Missing unit tests for `AUTH_CERT_VAULT` filter

**Location:** No `CertVaultAuthGatewayFilterFactoryTest.java` exists

The `CertVaultAuthGatewayFilterFactory` (175+ lines) has complex logic — PEM parsing, scoped vs. global fingerprint lookup, revoked/expired detection, 9 identity headers injected downstream — but zero test coverage.

### Issue 4.2: Missing unit tests for `CERT_ROTATION` filter

**Location:** No `CertRotationGatewayFilterFactoryTest.java` exists

The `CertRotationGatewayFilterFactory` has logic for active match → pass, inactive match → reject, unknown cert → reject, missing header → reject, but no tests validate these paths.

### Issue 4.3: Missing unit tests for `CERT_VAULT_EXPIRY_CHECK` filter

**Location:** No `CertVaultExpiryCheckGatewayFilterFactoryTest.java` exists

The `CertVaultExpiryCheckGatewayFilterFactory` has 5 distinct outcomes (no active cert → 503, all expired → 503, expiring soon + reject → 503, expiring soon + pass → inject headers, active → inject headers) with zero test coverage.

### Issue 4.4: Missing unit tests for `TENANT_CONTEXT` filter

**Location:** No `TenantContextGatewayFilterFactoryTest.java` exists

The `TenantContextGatewayFilterFactory` (399 lines) is one of the most complex filters — two modes (caller-provided vs auto-inject), JWT cross-validation, monthly quota enforcement via Redis, exchange attribute injection — with zero dedicated tests.

### Issue 4.5: Missing unit tests for `AUTH_MTLS` filter

**Location:** Only basic tests exist in `HeaderFilterFactoriesTest.java` — no dedicated `MtlsAuthGatewayFilterFactoryTest.java`

The mTLS auth filter's dual config paths (dynamic gateway config ref vs. legacy static YAML), PEM header parsing, and multi-mapping iteration lack focused test coverage.

**Fix for all 4.x issues:**
- Create dedicated test files for each filter following the established testing patterns in the codebase (e.g., `MockServerHttpRequest`/`MockServerWebExchange` + `StepVerifier`).
- Focus tests on:
  - Happy path (valid input → expected headers/pass-through)
  - Rejection paths (invalid/missing input → correct HTTP status and error code)
  - Edge cases (null config, blank logicalId, expired cert, etc.)
  - Config binding (ensure SCG property binder correctly populates the `Config` class)

---

## Initiative 5: Config Mapping Correctness — Fix Broken Config Bindings

**Priority: 🟡 Medium** | **Effort: S** | **Risk: Medium — user-configured values silently ignored**

### Issue 5.1: `RequestHeaderModifyGatewayFilterFactory` — `set` operation uses `builder.header()` which appends rather than replacing

**Location:** `routify-api-gateway/.../filter/RequestHeaderModifyGatewayFilterFactory.java`, line 53

```java
config.getSet().forEach((header, value) -> {
    builder.header(header, value);  // ServerHttpRequest.Builder.header() ADDS, does not overwrite
});
```

`ServerHttpRequest.Builder.header(name, value)` calls `headers.add(name, value)` under the hood — it does **not** replace existing values. If the incoming request already has a header `X-Custom: old-value`, the "set" operation produces `X-Custom: old-value, new-value` instead of `X-Custom: new-value`.

The `remove` step runs first (remove → set → add), so a workaround exists: users can add the same header to both `remove` and `set`. But the documented contract says "set overwrites any existing value" — which is incorrect.

**Impact:** The `set` operation on request headers does not actually overwrite existing values.

**Fix:**
Replace `builder.header(header, value)` with:
```java
builder.headers(h -> {
    h.remove(header);
    h.add(header, value);
});
```

### Issue 5.2: Dashboard `DEFAULT_CONFIGS` for `REQUEST_HEADER_MODIFY` and `RESPONSE_HEADER_MODIFY` use nested `Map` objects that need dotted-key flattening

**Location:** `routify-dashboard/src/modules/filters/filterConfigConstants.ts`, lines 50–51

```typescript
REQUEST_HEADER_MODIFY: { add: {}, set: {}, remove: {} },
RESPONSE_HEADER_MODIFY: { add: {}, set: {}, remove: {} },
```

When the user populates these maps in the dashboard and saves, the config is stored as:
```json
{ "set": { "X-Custom": "value" }, "add": {}, "remove": {} }
```

In `RouteDefinitionBuilder.flattenConfigValue()`, a `Map` value is expanded to dotted keys: `set.X-Custom=value`. The SCG binder then attempts to bind `set.X-Custom` to the `Config.set` property, which is `Map<String, String>`. This **works correctly** — the Spring Binder understands dotted keys for map bindings. No issue here.

However, the `remove` map is documented as "key = header name, value is ignored", but the dashboard sends it as `{ "remove": { "X-Header": "" } }`. This is correct and functions as expected.

**Status:** ✅ No fix needed — verified working correctly.

---

## Initiative 6: Deprecated & Unused Filter Cleanup — Aligned Backend + Frontend Retirement

**Priority: 🟡 Medium** | **Effort: M** | **Risk: Medium — incomplete cleanup causes silent failures and operator confusion**

### Background

The `FilterType` enum contains **12 deprecated values** that were superseded by newer implementations or dropped entirely. These legacy entries are preserved solely for backward compatibility with rows already in the `filter_definition` database table. However, no layer currently enforces retirement consistently: the admin-api still accepts them for new filter creation, the gateway silently executes some or drops others, the import/export pipeline re-imports them without warnings, and the dashboard has no visibility into their existence. This creates a state where operators may unknowingly rely on deprecated filters that either do nothing or use inferior legacy behaviour.

The 12 deprecated `FilterType` values and their current gateway behaviour are:

| Deprecated type | Gateway behaviour | Modern replacement |
|---|---|---|
| `AUTH_NONE` | Yields `null` — silently dropped | *(remove the filter)* |
| `RATE_LIMIT_TOKEN_BUCKET` | Maps to SCG `RequestRateLimiter` — functional but less capable | `RATE_LIMIT_FIXED_WINDOW` or `RATE_LIMIT_SLIDING_WINDOW` |
| `PATH_REWRITE` | Maps to SCG `RewritePath` — functional | Use route-level `stripPrefix` or conditional routing |
| `PATH_STRIP_PREFIX` | Maps to SCG `StripPrefix` — functional | Use route-level `stripPrefix` field |
| `PATH_ADD_PREFIX` | Maps to SCG `PrefixPath` — functional | Use route-level config or `REQUEST_HEADER_MODIFY` |
| `QUERY_PARAM_MODIFY` | Yields `null` + warning — **no-op** | *(no replacement — feature was dropped)* |
| `BODY_JSONATA_TRANSFORM` | Yields `null` + warning — **no-op** | `BODY_JOLT_TRANSFORM` |
| `BODY_SPEL_TRANSFORM` | Yields `null` + warning — **no-op** | `BODY_JOLT_TRANSFORM` or `CUSTOM_SPEL` |
| `VALIDATE_REGEX` | Yields `null` + warning — **no-op** | `VALIDATE_JSON_SCHEMA` or `CUSTOM_SPEL` |
| `VALIDATE_SIZE` | Maps to SCG `RequestSize` — functional but limited | `REQUEST_SIZE_LIMIT` |
| `CIRCUIT_BREAKER` | Maps to SCG `CircuitBreaker` — functional but missing WebSocket state | `CIRCUIT_BREAKER_V2` |
| `RETRY` | Maps to SCG `Retry` — functional but missing backoff/jitter | `RETRY_V2` |

**Of the 12 deprecated types, 5 silently do nothing at the gateway.** The remaining 7 are functional via legacy SCG built-in filters but lack the feature richness (Redis-backed state, WebSocket broadcasting, Micrometer metrics) of their modern replacements.

The goal of this initiative is to **align all layers** — from the Java enum to the admin-api validation to the gateway runtime to the export/import pipeline to the dashboard UI — so that deprecated filters are visible, blocked from new creation, safely migrated where possible, and eventually removable once no database rows reference them.

### Issue 6.1: Admin-API allows creation of deprecated filter types

**Location:** `routify-admin-api/.../client/RouteFilterMessagingClient.java`, line 258

The `sendCreateFilter()` method calls `FilterType.valueOf(req.filterType())`, which accepts any valid enum value — including all 12 deprecated types. A direct API call with `filterType: "CIRCUIT_BREAKER"` is silently persisted and may later be attached to routes.

**Impact:** New filters can be created with deprecated types. 5 of 12 types do absolutely nothing at the gateway; the other 7 use legacy behaviour that diverges from what operators expect from the modern dashboard experience.

**Fix (backend — `routify-admin-api`):**
1. Add a `DEPRECATED_TYPES` constant set in `FilterType` or in a shared utility in `routify-common`:
   ```java
   public static final Set<FilterType> DEPRECATED = Set.of(
       AUTH_NONE, RATE_LIMIT_TOKEN_BUCKET, PATH_REWRITE, PATH_STRIP_PREFIX,
       PATH_ADD_PREFIX, QUERY_PARAM_MODIFY, BODY_JSONATA_TRANSFORM,
       BODY_SPEL_TRANSFORM, VALIDATE_REGEX, VALIDATE_SIZE, CIRCUIT_BREAKER, RETRY
   );
   ```
2. In `RouteFilterMessagingClient.sendCreateFilter()`, after `FilterType.valueOf()`, reject deprecated types:
   ```java
   if (FilterType.DEPRECATED.contains(type)) {
       throw new RoutifyException.Validation(
           "Filter type '%s' is deprecated and cannot be used for new filters. Use '%s' instead."
               .formatted(type, suggestedReplacement(type)));
   }
   ```
3. In the `ImportService.validateFilters()` loop (line 158), after the `FilterType.valueOf()` check, add a **warning** (not a hard error) for deprecated types in imported configs so that GitOps imports don't break existing repos but operators are notified.

### Issue 6.2: Gateway `RouteDefinitionBuilder` deprecated branches lack consistent logging and metrics

**Location:** `routify-api-gateway/.../routing/RouteDefinitionBuilder.java`, lines 228–341

Seven deprecated types silently execute legacy SCG built-in filters with no warning log. Five others yield `null` with a warning log but no metric. There is no way for operators to discover which routes use deprecated filters without querying the database directly.

**Impact:** Operators cannot tell from gateway logs or Grafana dashboards whether deprecated filters are in use across their fleet.

**Fix (backend — `routify-api-gateway`):**
1. Add a `log.warn()` to every deprecated `case` branch that currently produces a filter silently:
   ```java
   case "RATE_LIMIT_TOKEN_BUCKET" -> {
       log.warn("Deprecated filter type RATE_LIMIT_TOKEN_BUCKET on route {} — "
               + "migrate to RATE_LIMIT_FIXED_WINDOW or RATE_LIMIT_SLIDING_WINDOW",
               filter.filterType());
       // ... existing logic
   }
   ```
   Apply to: `RATE_LIMIT_TOKEN_BUCKET`, `PATH_REWRITE`, `PATH_STRIP_PREFIX`, `PATH_ADD_PREFIX`, `VALIDATE_SIZE`, `CIRCUIT_BREAKER`, `RETRY`.
2. Add a Micrometer counter `routify.gateway.deprecated_filter_used` (tagged by `filterType`) so operators can set up Prometheus alerts when deprecated filters are still executing.
3. Keep the legacy `case` branches functional — they must continue to work for existing DB rows until operators migrate them.

### Issue 6.3: Import/export pipeline propagates deprecated filters without warnings

**Location:**
- `routify-admin-api/.../service/ExportService.java` — exports all filter definitions including deprecated types
- `routify-admin-api/.../service/ImportService.java` — imports deprecated types without any warning
- `routify-gitops-agent` — syncs YAML manifests that may contain deprecated filter types

When a tenant exports their configuration, deprecated filter definitions are included in the export bundle. When that bundle is re-imported (or synced via GitOps), deprecated filters are silently created/updated with no visibility.

**Impact:** Deprecated filters circulate between environments indefinitely via export → import cycles without operators being aware they should migrate.

**Fix (backend — `routify-admin-api`):**
1. In `ExportService`, add a `deprecated: true` flag to the `FilterExportEntry` when the `filterType` is in the deprecated set. This is a non-breaking addition to the export schema.
2. In `ImportService`, when a filter has a deprecated type, add a warning to the diff response (similar to how the GitOps mock already shows `'Route "legacy-api" has deprecated filter type PATH_REWRITE'`):
   ```java
   if (FilterType.DEPRECATED.contains(filterType)) {
       warnings.add("Filter '%s' uses deprecated type %s — consider migrating to %s"
           .formatted(importFilter.name(), filterType, suggestedReplacement(filterType)));
   }
   ```
3. Imports should **not hard-fail** on deprecated types — they must remain importable for backward compatibility. The warnings are informational.

### Issue 6.4: Dashboard has no visibility into deprecated filters attached to existing routes

**Location:**
- `routify-dashboard/src/types/index.ts` — `FilterType` TS union correctly excludes deprecated types
- `routify-dashboard/src/modules/filters/filterRegistry.ts` — `FILTER_REGISTRY` only contains 29 active types
- `routify-dashboard/src/modules/filters/filterConfigConstants.ts` — `DEFAULT_CONFIGS` only contains 29 active types
- `routify-dashboard/src/modules/workflow-builder/constants/nodeMetadata.tsx` — has a `legacy` placeholder (line 139) but it is empty

The dashboard's `FilterType` TypeScript union intentionally excludes deprecated types, which means the dashboard will never **create** deprecated filters. However, the backend can still return filter definitions with deprecated types (from existing DB rows), and the dashboard has no strategy for displaying them:

- The filter list page will show them with no label, icon, or category (falls back to `undefined`).
- The workflow builder will render them with `DEFAULT_FILTER_META` (generic gray icon) but with no tooltip explaining they are deprecated.
- The filter detail/edit form will break or show an empty type selector because the deprecated type is not in the `FILTER_REGISTRY`.

**Impact:** Existing deprecated filters appear as broken, unlabelled entries in the dashboard, with no guidance for operators on what to do.

**Fix (frontend — `routify-dashboard`):**
1. Add a `DEPRECATED_FILTER_TYPES` constant in `src/types/index.ts` or `src/modules/filters/filterRegistry.ts`:
   ```typescript
   export const DEPRECATED_FILTER_TYPES = new Set([
     'AUTH_NONE', 'RATE_LIMIT_TOKEN_BUCKET', 'PATH_REWRITE', 'PATH_STRIP_PREFIX',
     'PATH_ADD_PREFIX', 'QUERY_PARAM_MODIFY', 'BODY_JSONATA_TRANSFORM',
     'BODY_SPEL_TRANSFORM', 'VALIDATE_REGEX', 'VALIDATE_SIZE', 'CIRCUIT_BREAKER', 'RETRY',
   ] as const)
   ```
2. In the **filter list page** (`FiltersPage.tsx`), when a filter's `filterType` is in `DEPRECATED_FILTER_TYPES`, render a deprecation badge (e.g., amber "Deprecated" pill) and a tooltip with the suggested replacement.
3. In the **workflow builder** (`nodeMetadata.tsx`), populate the currently empty `legacy` record with fallback metadata (amber colour, ⚠️ icon) for deprecated types so they render with a visible "deprecated" visual state instead of generic gray.
4. In the **filter detail/edit view**, if the filter type is deprecated, show a read-only banner:
   > ⚠️ This filter uses a deprecated type (`CIRCUIT_BREAKER`). It will continue to function but should be replaced with **Circuit Breaker v2** (`CIRCUIT_BREAKER_V2`). [Create Replacement →]
5. **Do not add deprecated types to the `FilterType` TS union** — they should remain excluded from the create form's type selector. Only the display/viewing code needs to handle them gracefully.

### Issue 6.5: No Flyway migration or admin tooling to discover and bulk-migrate deprecated filters

**Location:**
- `routify-route-service/.../db/migration/V3__monitoring_views_and_procedures.sql` — the `v_filter_inventory` view already includes an `is_deprecated_type` flag (lines 86–95), but there is no corresponding API endpoint, admin-api query, or dashboard page that exposes this data.

**Impact:** Operators have no self-service way to discover how many deprecated filters exist across tenants, which routes use them, or to bulk-migrate them to modern equivalents.

**Fix (cross-cutting):**
1. **Backend (route-service):** Add a `@RabbitListener` query handler that returns deprecated filter usage stats per tenant (leveraging the existing `v_filter_inventory` view's `is_deprecated_type` flag). Add the corresponding `QueryRequest`/`QueryResponse` records in `routify-common`.
2. **Backend (admin-api):** Expose a `GET /api/admin/filters/deprecated-usage` endpoint that calls the new RPC query. Returns a summary: `{ totalDeprecated: number, byType: { CIRCUIT_BREAKER: 3, PATH_REWRITE: 1, ... }, affectedRoutes: [...] }`.
3. **Frontend (dashboard):** Add a deprecation summary card to the Filters page (or a sub-tab "Deprecated") showing counts and affected routes, with one-click navigation to each filter's detail page for migration.
4. **(Future):** Consider a bulk-migration CLI command or admin-api endpoint that auto-creates the modern replacement filter (with equivalent config mapping) and re-attaches it to all routes that use the deprecated one.

### Issue 6.6: `routify-common` `FilterType` enum Javadoc does not document replacement types

**Location:** `routify-common/.../domain/FilterType.java`, lines 248–271

Each deprecated value has `@Deprecated` and a Javadoc comment saying "No gateway factory implementation", but none of them document which modern `FilterType` should be used instead. This makes it harder for developers adding new code to know the correct migration path.

**Impact:** Developer friction — contributors must cross-reference the `RouteDefinitionBuilder` switch or this roadmap to find the replacement.

**Fix (backend — `routify-common`):**
Update each deprecated value's Javadoc to include `@see` or `@deprecated Use {@link #REPLACEMENT_TYPE} instead`:
```java
/** @deprecated Use {@link #RATE_LIMIT_FIXED_WINDOW} or {@link #RATE_LIMIT_SLIDING_WINDOW}. */
@Deprecated RATE_LIMIT_TOKEN_BUCKET,

/** @deprecated Use {@link #CIRCUIT_BREAKER_V2}. */
@Deprecated CIRCUIT_BREAKER,

/** @deprecated Use {@link #RETRY_V2}. */
@Deprecated RETRY,
```

---

## Initiative 7: Observability & Resilience Improvements

**Priority: 🟢 Low** | **Effort: M** | **Risk: Low — improvements, not bugs**

### Issue 7.1: `CorrelationIdGatewayFilterFactory` does not validate incoming correlation IDs

**Location:** `routify-api-gateway/.../filter/CorrelationIdGatewayFilterFactory.java`, line 58

```java
String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
if (correlationId == null || correlationId.isBlank()) {
    correlationId = UUID.randomUUID().toString();
}
```

Incoming correlation IDs are accepted as-is with no format validation. A malicious client could send an arbitrarily long string (e.g., 1 MB of data) as `X-Correlation-Id`, which would be propagated to all downstream services, logged in MDC, and included in Kafka telemetry events.

**Impact:** Potential log injection or DoS via oversized correlation IDs.

**Fix:**
- Add length validation (e.g., max 128 characters — UUIDs are 36 chars).
- Optionally validate UUID format or reject IDs with control characters.
- If the incoming value fails validation, generate a new UUID.

### Issue 7.2: `SecurityHeadersGatewayFilterFactory` applies headers directly on `exchange.getResponse()` before `chain.filter()`

**Location:** `routify-api-gateway/.../filter/SecurityHeadersGatewayFilterFactory.java`, line 63

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    applySecurityHeaders(exchange.getResponse(), configLoader.getConfig());
    return chain.filter(exchange);
}
```

Headers are set on the response **before** the downstream chain executes. If any downstream filter or the upstream service sets a conflicting header (e.g., its own `X-Frame-Options`), the security header is silently overwritten. This is opposite to the intent — security headers should be the last word.

**Impact:** Security headers can be overridden by upstream services or downstream filters.

**Fix:**
- Use `exchange.getResponse().beforeCommit(() -> { applySecurityHeaders(...); return Mono.empty(); })` to apply security headers as the very last step before response commitment.
- Alternatively, use the `.then(Mono.fromRunnable(...))` pattern that `ResponseHeaderModifyGatewayFilterFactory` uses (with the caveat from Issue 2.2 that this can be too late for streaming responses — prefer `beforeCommit`).

### Issue 7.3: `SpelCustomGatewayFilterFactory` publishes Kafka audit events synchronously on the Netty event loop

**Location:** `routify-api-gateway/.../filter/SpelCustomGatewayFilterFactory.java`, line 291

```java
kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS, key, auditPayload);
```

`KafkaTemplate.send()` is non-blocking (it returns a `CompletableFuture`), so this is technically safe. However, the `publishAuditEvent()` method runs inside the reactive filter lambda (on the Netty I/O thread). If the Kafka producer's buffer is full, `send()` can block on `buffer.memory` allocation.

**Impact:** Under extreme load with a backed-up Kafka producer, audit event publishing could briefly block the event loop.

**Fix:**
- Wrap the `kafkaTemplate.send()` call in a try-catch that's already there (good).
- Optionally offload to `Schedulers.boundedElastic()` for full isolation:
  ```java
  Mono.fromRunnable(() -> kafkaTemplate.send(...))
      .subscribeOn(Schedulers.boundedElastic())
      .subscribe();
  ```

---

## Implementation Priority Matrix

| # | Initiative | Priority | Effort | Scope |
|---|---|---|---|---|
| **1** | Input Validation | ✅ Done | S | admin-api |
| **2** | Reactive Safety | ✅ Done | M | api-gateway |
| **3** | Type Safety | ✅ Done | S | api-gateway |
| **4** | Test Coverage | ✅ Done | M | api-gateway (tests) |
| **5** | Config Mapping | 🟡 Medium | S | api-gateway |
| **6** | Deprecated & Unused Filter Cleanup | 🟡 Medium | M | **all layers** — common, admin-api, api-gateway, route-service, dashboard |
| **7** | Observability | 🟢 Low | M | api-gateway |

### Suggested Sprint Breakdown

**Sprint 1 (Critical — 1 week):**
- Issue 1.1: Add `@NotBlank` + `FilterType` enum validation to `CreateFilterRequest`
- Issue 2.1: Fix `ResponseHeaderRewrite` blocking `.get()` call
- Issue 2.2: Fix `ResponseHeaderModify` and `ResponseHeaderRewrite` `.then()` timing for response headers
- Issue 5.1: Fix `RequestHeaderModify` `set` operation to actually overwrite

**Sprint 2 (High — 1 week):**
- Issue 3.1: Refactor `RouteDefinitionBuilder` switch to use `FilterType` enum
- Issue 3.2: Create `RouteDefinitionBuilderTest` with parameterized tests for all 29 active filter types
- Issue 1.2: Add basic required-field validation for filter configs (at least for cert/auth filters)

**Sprint 3 (Medium — 1–2 weeks):**
- Issues 4.1–4.5: Write unit tests for `AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`, `TENANT_CONTEXT`, `AUTH_MTLS`
- Issue 7.2: Fix `SecurityHeaders` header application timing
- Issue 6.6: Update deprecated enum Javadoc with `@see` replacement references (quick win, unlocks all other 6.x work)
- Issue 6.1: Add `DEPRECATED` set to `FilterType`, block creation of deprecated filters in admin-api
- Issue 6.3: Add import/export warnings for deprecated filter types

**Sprint 4 (Medium — 1 week):**
- Issue 6.4: Dashboard deprecated filter visibility — badges, workflow builder metadata, detail-page banners
- Issue 6.2: Add deprecation warning logs + Micrometer counter to gateway deprecated `case` branches
- Issue 6.5: Expose deprecated filter usage stats via RabbitMQ query → admin-api endpoint → dashboard card
- Issue 7.1: Validate incoming correlation ID length
- Issue 7.3: Offload SpEL audit Kafka send to bounded elastic

---

## Appendix: Filters Verified Working Correctly

The following filters were thoroughly reviewed and have no identified issues:

- **AUTH_API_KEY** — Redis + header/query param validation, well-tested
- **AUTH_BASIC** — Bcrypt password hashing, dedicated hardening tests
- **AUTH_JWT** — RS256/HS256 validation, claim-to-header injection, dedicated hardening tests
- **AUTH_OAUTH2** — Token introspection via WebClient, reactive, well-tested
- **AUTH_CLIENT_ID** — Indexed values binding, dedicated test
- **DOWNSTREAM_BASIC_AUTH** — Base64 encoding, straightforward
- **DOWNSTREAM_BEARER_CC** — Dual-path config (named provider + direct), reactive WebClient
- **OAUTH2_TOKEN_RELAY** — RFC 8693, Caffeine cache, fallback modes, dedicated test
- **RATE_LIMIT_FIXED_WINDOW** / **RATE_LIMIT_SLIDING_WINDOW** — Redis-backed, well-tested
- **BODY_JOLT_TRANSFORM** — Chainr pre-compilation, REQUEST/RESPONSE/BOTH phases
- **VALIDATE_JSON_SCHEMA** — JSON Schema Draft-07, pre-compiled
- **REQUEST_SIZE_LIMIT** — Content-Length + actual body check
- **GRAPHQL_DEPTH_LIMIT** — AST parsing, depth/complexity/alias/batch limits
- **RESPONSE_CACHE** — Redis-backed, Cache-Control respect, TTL, key strategies
- **REQUEST_DECOMPRESS** — gzip/br/zstd, zip bomb protection
- **IDEMPOTENCY_KEY** — Redis dedup, 409 on concurrent duplicates
- **TIMEOUT** — `Mono.timeout()`, non-blocking
- **CIRCUIT_BREAKER_V2** — Resilience4j, WebSocket state broadcast
- **RETRY_V2** — Exponential backoff, jitter, idempotency-aware
- **CONDITIONAL_ROUTE** / **USER_ID_PAYLOAD_ROUTING** / **GEO_ROUTE** — Well-tested
- **IP_ACCESS_CONTROL** — CIDR matching, IPv4/IPv6, dedicated test
- **CUSTOM_METRIC** / **BODY_SIZE_METRIC** — Micrometer, lightweight
- **WEBHOOK_NOTIFY** — HMAC signing, cooldown, fire-and-forget
- **MOCK_RESPONSE** — Template interpolation, simulated latency
- **AI_FILTER** / **AI_MODIFIER** — `Schedulers.boundedElastic()` offloading, fallback, body streaming

