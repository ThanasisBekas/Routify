# Routify — Gateway Filters Backlog Roadmap

> **Type:** Evergreen backlog (not time-boxed to a single quarter)  
> **Platform version baseline:** 2.1.0-SNAPSHOT (post-Q3 2026)  
> **Prerequisite:** Q2 deprecated `FilterType` removal complete; Q3 initiatives (API Keys, Route Promotion, Webhooks, Granular RBAC, Health Dashboard v2) delivered.  
> **Goal:** Systematically improve, harden, and extend the Routify gateway filter chain — refactoring existing filters for consistency, testability, and performance, while introducing new filter types that close feature gaps and unlock advanced use cases.

Each initiative has a dedicated design document in [`docs/roadmap/initiatives/`](./initiatives/) (prefixed `gf-01` through `gf-22`).

---

## Table of Contents

- **Part A — Refactoring & Improvements to Existing Filters** (Initiatives 1–8)
- **Part B — New Gateway Filters** (Initiatives 9–22)
- [Dependency & Sequencing Map](#dependency--sequencing-map)
- [Risk Register](#risk-register)
- [Success Metrics](#success-metrics)

---

## Part A — Refactoring & Improvements to Existing Filters

| # | Initiative | Category | Priority | Impact |
|---|-----------|----------|----------|--------|
| 1 | ✅ [Extract Shared Key Resolver](#1-extract-shared-key-resolver) | Rate Limiting | High | Eliminates duplicated key resolution logic across 3 rate limit filters |
| 2 | ✅ [Unified Error Response Builder](#2-unified-error-response-builder) | Cross-cutting | High | Consistent RFC 9457 ProblemDetail responses from all filters with shared `Retry-After`, rate-limit headers |
| 3 | ✅ [Rate Limiter `X-RateLimit-*` Response Headers](#3-rate-limiter-x-ratelimit--response-headers) | Rate Limiting | Medium | Standard `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` on every response |
| 4 | ✅ [JwtAuth Filter Hardening](#4-jwtauth-filter-hardening) | Authentication | High | Remove dev-mode unsigned JWT path, add JWKS rotation, issuer/audience validation enforcement |
| 5 | ✅ [Jolt Transform Response-Phase Support](#5-jolt-transform-response-phase-support) | Body Transform | Medium | Complete the `phase=RESPONSE` stub; enable JSON-to-JSON transformation on upstream responses |
| 6 | ✅ [RequestLogger Performance & Configurability](#6-requestlogger-performance--configurability) | Observability | Medium | Reduce body-capture overhead, add sampling, header allowlists, and structured JSON log format |
| 7 | ✅ [SpEL Filter Sandboxing & Security](#7-spel-filter-sandboxing--security) | Security | High | Restrict SpEL context to a safe subset; prevent ClassLoader/Runtime escapes |
| 8 | ✅ [AI Filter Streaming Body Support](#8-ai-filter-streaming-body-support) | AI | Medium | Stream request body incrementally to AI service for large payloads instead of buffering entire body |

---

### 1. Extract Shared Key Resolver

**Design doc:** [`initiatives/gf-01-shared-key-resolver.md`](./initiatives/gf-01-shared-key-resolver.md)  
**Category:** Rate Limiting  
**Affects:** `FixedWindowRateLimitGatewayFilterFactory`, `SlidingWindowRateLimitGatewayFilterFactory`, `RouteDefinitionBuilder` (token-bucket key resolver)

#### Problem
The `resolveKey()` method is duplicated verbatim in both `FixedWindowRateLimitGatewayFilterFactory` and `SlidingWindowRateLimitGatewayFilterFactory` (~25 lines each). The `RouteDefinitionBuilder` has a third, separate key resolver mapping for the SCG built-in token bucket. Any new key resolver strategy (e.g., `ROUTE`, `GEO`, composite keys) must be added in three places.

#### Proposed Solution
1. **Extract `RateLimitKeyResolver` utility class** — a `@Component` in `io.routify.gateway.filter.ratelimit` with a single `resolve(ServerWebExchange, String strategy): String` method.
2. **Support new strategies** — add `ROUTE` (key per route ID), `HEADER:<name>` (arbitrary header value), and `COMPOSITE:<a>:<b>` (concatenation of two strategies) patterns.
3. **Inject into both filter factories** via constructor.
4. **Deprecate** the inline `resolveKey()` methods; remove in next minor version.
5. **Add `ROUTE` strategy** to both custom factories and add a SpEL bean for the SCG token bucket (`#{@routeKeyResolver}`).

#### Acceptance Criteria
- [x] Single `RateLimitKeyResolver` class used by all 3 rate limiter paths
- [x] `ROUTE`, `HEADER:<name>`, `COMPOSITE` strategies supported
- [x] Existing `IP`, `USER`, `TENANT`, `API_KEY`, `TENANT_USER` strategies unchanged
- [x] Unit tests for every strategy variant

---

### 2. Unified Error Response Builder

**Design doc:** [`initiatives/gf-02-unified-error-response.md`](./initiatives/gf-02-unified-error-response.md)  
**Category:** Cross-cutting  
**Affects:** All 30 gateway filter factories

#### Problem
Every filter that short-circuits a request (auth failures, rate limit, validation, AI block, SpEL reject) writes its own RFC 9457 response body using local `String.formatted()` templates. Inconsistencies include:
- Some include `errorCode`, others don't.
- JSON escaping of user-supplied `detail` strings is ad hoc (`replace("\"", "'")` in AI filter, `replace("\"", "\\\"")` in SpEL filter).
- `Content-Type` header is set to `application/problem+json` in some filters, `application/json` in others.
- `Retry-After` header is missing from rate limit rejection responses.

#### Proposed Solution
1. **Create `GatewayProblemResponse` utility class** with a reactive builder API:
   ```java
   GatewayProblemResponse.status(HttpStatus.TOO_MANY_REQUESTS)
       .errorCode("RATE_LIMIT_EXCEEDED")
       .detail("Rate limit exceeded for key %s", clientKey)
       .header("Retry-After", retryAfterSeconds)
       .header("X-RateLimit-Limit", maxRequests)
       .write(exchange);
   ```
2. **Centralize JSON escaping** — use Jackson `ObjectMapper` for body serialization instead of string templates, ensuring safe encoding of all fields.
3. **Standardize `Content-Type`** — always `application/problem+json` per RFC 9457.
4. **Migrate all filters** to use the shared builder (one filter at a time, non-breaking).

#### Acceptance Criteria
- [x] `GatewayProblemResponse` class with builder API
- [x] All 12 filter factories that produce error responses migrated
- [x] JSON injection tests (malicious `detail` strings) pass
- [x] `Content-Type` is always `application/problem+json`
- [x] `Retry-After` header present on all 429 responses

---

### 3. Rate Limiter `X-RateLimit-*` Response Headers

**Design doc:** [`initiatives/gf-03-ratelimit-headers.md`](./initiatives/gf-03-ratelimit-headers.md)  
**Category:** Rate Limiting  
**Affects:** `FixedWindowRateLimitGatewayFilterFactory`, `SlidingWindowRateLimitGatewayFilterFactory`

#### Problem
Neither rate limiter emits standard `X-RateLimit-*` headers on successful responses. API consumers cannot proactively slow down before hitting the limit. Only the rejection response includes a basic `X-RateLimit-Window` header.

#### Proposed Solution
1. **On every response** (allow and reject), inject:
   - `X-RateLimit-Limit: <maxRequests>` — the window limit
   - `X-RateLimit-Remaining: <maxRequests - count>` — remaining budget
   - `X-RateLimit-Reset: <epoch-seconds>` — window reset timestamp
2. **Modify the Lua scripts** to return both `count` and `ttl` (remaining window time) so the gateway can compute `Reset` without a separate Redis call.
3. **Add `includeHeaders` config flag** (default `true`) to allow operators to suppress headers for internal APIs.
4. **Emit `Retry-After` on 429** with the number of seconds until the window resets.

#### Acceptance Criteria
- [x] `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` on every 2xx/4xx response
- [x] `Retry-After` on 429 responses
- [x] Lua scripts return `{count, ttl}` tuple
- [x] `includeHeaders=false` suppresses all rate limit headers
- [x] Integration test validates header values against Redis state

---

### 4. JwtAuth Filter Hardening

**Design doc:** [`initiatives/gf-04-jwt-hardening.md`](./initiatives/gf-04-jwt-hardening.md)  
**Category:** Authentication  
**Affects:** `JwtAuthGatewayFilterFactory`

#### Problem
1. **Dev-mode unsigned path** — when `routify.jwt.public-key` is empty, the filter decodes the JWT payload *without signature verification*. This is a security risk if the config is accidentally left unset in production.
2. **No JWKS URI support** — the public key is loaded once from a Base64 config property. Key rotation requires a gateway restart.
3. **Issuer/audience claims are config params** but never actually validated against the parsed JWT.
4. **HS256 algorithm advertised in config** but the implementation only handles RS256.

#### Proposed Solution
1. **Remove unsigned decode path** — if `routify.jwt.public-key` is blank AND `routify.jwt.jwks-uri` is blank, reject all JWT requests with a clear `SERVER_MISCONFIGURED` error (fail-closed). Log a startup WARNING.
2. **Add JWKS URI support** — new config property `routify.jwt.jwks-uri`. Use `io.jsonwebtoken:jjwt-jackson` `JwkSetSource` to fetch and cache keys with 5-minute refresh. Falls back to static key if both are configured.
3. **Enforce issuer/audience** — when `config.issuer` is set, validate `claims.getIssuer()`. When `config.audience` is set, validate `claims.getAudience()`.
4. **Remove HS256 from supported algorithms** — Routify uses RS256 exclusively. Simplify to RS256-only.
5. **Add `require-jti` config flag** (default `true`) — reject tokens without a `jti` claim instead of logging a warning and skipping blocklist check.

#### Acceptance Criteria
- [x] No unsigned JWT decode path in production
- [x] JWKS URI support with key caching and rotation
- [x] Issuer and audience claims validated when configured
- [x] `require-jti=true` by default; tokens without `jti` rejected
- [x] Startup health check fails if neither public key nor JWKS URI is configured

---

### 5. Jolt Transform Response-Phase Support

**Design doc:** [`initiatives/gf-05-jolt-response-phase.md`](./initiatives/gf-05-jolt-response-phase.md)  
**Category:** Body Transformation  
**Affects:** `JoltTransformGatewayFilterFactory`

#### Problem
The `phase` config param accepts `RESPONSE` but the implementation only handles `REQUEST`. Response transformation silently falls through to request mode.

#### Proposed Solution
1. **Implement `ServerHttpResponseDecorator`** that intercepts `writeWith()`, joins the response body, applies the Jolt `Chainr`, and rewrites the body with the transformed JSON.
2. **Update `Content-Length`** header to reflect the transformed body size.
3. **Respect `Content-Type`** — only transform `application/json` responses; pass others through unchanged.
4. **Add `maxBodySize` config param** (default 1 MB) — skip transformation for bodies exceeding the limit (log warning, pass through original).
5. **Support `BOTH` phase** — apply request Jolt spec, then a separate response Jolt spec (new config field `responseSpec`).

#### Acceptance Criteria
- [x] `phase=RESPONSE` transforms upstream response body before returning to client
- [x] `phase=BOTH` transforms request and response with independent specs
- [x] `Content-Length` updated on transformed responses
- [x] Non-JSON responses pass through unchanged
- [x] Bodies exceeding `maxBodySize` pass through with a warning log

---

### 6. RequestLogger Performance & Configurability

**Design doc:** [`initiatives/gf-06-requestlogger-improvements.md`](./initiatives/gf-06-requestlogger-improvements.md)  
**Category:** Observability  
**Affects:** `RequestLoggerGatewayFilterFactory`

#### Problem
1. **Body capture overhead** — when `logRequestBody=true`, the entire body is eagerly buffered into memory, which can cause OOM for large file uploads.
2. **No sampling** — every request generates a telemetry event, which under high traffic can overwhelm the `routify.request.telemetry` Kafka topic.
3. **Flat header sanitization** — `REDACTED_HEADERS` is hardcoded; operators cannot customise which headers to capture or suppress.
4. **No structured JSON log** — `log.info()` messages use positional interpolation, making log aggregation (ELK, Loki) harder.

#### Proposed Solution
1. **Add `maxBodyCaptureBytes` config param** (default 4096) with a hard upper bound of 64 KB. Bodies beyond the limit are truncated with a `[TRUNCATED]` marker.
2. **Add `samplingRate` config param** (default `1.0`, range 0.0–1.0) — probabilistic sampling using `ThreadLocalRandom` to control telemetry volume. Rate-limited sampling: when `samplingRate < 1.0`, only publish every Nth event.
3. **Add `headerAllowlist` / `headerDenylist` config params** — operators control exactly which request/response headers are captured in telemetry events. Deny overrides allow.
4. **Structured log format** — emit a JSON-structured `log.info()` message via SLF4J MDC enrichment (`method`, `path`, `status`, `elapsedMs`, `correlationId`).
5. **Add `skipPaths` config param** — regex patterns for paths to exclude from logging (e.g., `/actuator/**`, `/health`).

#### Acceptance Criteria
- [x] Body capture capped at `maxBodyCaptureBytes` (hard limit 64 KB)
- [x] `samplingRate=0.1` produces ~10% of telemetry events
- [x] `headerAllowlist` and `headerDenylist` work as documented
- [x] `skipPaths` excludes matching requests from logging
- [x] No behaviour change when all config is at defaults

---

### 7. SpEL Filter Sandboxing & Security

**Design doc:** [`initiatives/gf-07-spel-sandboxing.md`](./initiatives/gf-07-spel-sandboxing.md)  
**Category:** Security  
**Affects:** `SpelCustomGatewayFilterFactory`

#### Problem
The SpEL expression context exposes `#request` (the full `ServerHttpRequest` object), which can be used to access `getClass().getClassLoader()` and escape the sandbox. A malicious operator with filter-write permissions could execute arbitrary code on the gateway JVM.

#### Proposed Solution
1. **Replace `StandardEvaluationContext`** with `SimpleEvaluationContext.forReadOnlyDataBinding()` which disallows type references, constructors, and method invocation on arbitrary objects.
2. **Restrict context variables** to primitives and immutable types only:
   - `#headers` — `Map<String, String>` (unchanged)
   - `#params` — `Map<String, String>` (unchanged)
   - `#method` — `String` (unchanged)
   - `#path` — `String` (unchanged)
   - `#contentType` — `String` (new)
   - `#clientIp` — `String` (new)
   - **Remove `#request`** — the full `ServerHttpRequest` object is no longer exposed.
3. **Add expression allowlist** — optional `allowedFunctions` config that restricts which SpEL functions can be called (default: string methods only).
4. **Add expression complexity limit** — reject expressions longer than 500 characters or with more than 5 nested property accessors.
5. **Audit log** — emit a `CUSTOM_SPEL_EVALUATED` event with the expression, result, and evaluation time for security auditing.

#### Acceptance Criteria
- [x] `SimpleEvaluationContext` used — no access to `Class`, `Runtime`, `ProcessBuilder`
- [x] `#request` variable removed from context
- [x] Expression length limit enforced (configurable, default 500 chars)
- [x] Audit event emitted on every evaluation
- [x] Existing `#headers`, `#params`, `#method`, `#path` expressions work unchanged

---

### 8. AI Filter Streaming Body Support

**Design doc:** [`initiatives/gf-08-ai-filter-streaming.md`](./initiatives/gf-08-ai-filter-streaming.md)  
**Category:** AI  
**Affects:** `AiGatewayFilterFactory`, `AiModifierGatewayFilterFactory`

#### Problem
When `includeBody=true`, the AI filter reads the body excerpt from an exchange attribute (`AI_FILTER_BODY_EXCERPT`) that must be pre-cached by an earlier filter. For large request bodies, this buffering adds latency and memory pressure. The `maxBodyBytes=512` default is too small for useful context.

#### Proposed Solution
1. **Inline body reading** — the AI filter itself reads and caches the first `maxBodyBytes` of the request body reactively using `DataBufferUtils.join()` + `limitRate()`, eliminating the dependency on a separate body-caching filter.
2. **Increase default `maxBodyBytes`** to 2048 (2 KB) — large enough for meaningful JSON payloads, small enough to avoid memory issues.
3. **Content-type awareness** — only include body for `application/json`, `text/plain`, `application/xml`. Skip binary content types entirely.
4. **Body hash for caching** — compute SHA-256 of the body excerpt and include it in the RPC request, enabling the AI service to cache verdicts per body hash instead of exact body match.
5. **Re-emit body for downstream** — after reading, re-wrap the body bytes in a `ServerHttpRequestDecorator` so upstream services still receive the full payload.

#### Acceptance Criteria
- [x] AI filter reads body inline without requiring a pre-caching filter
- [x] Binary content types are automatically skipped
- [x] Body hash included in RPC request for cache keying
- [x] Upstream services receive the original full body unchanged
- [x] `maxBodyBytes=2048` by default

---

## Part B — New Gateway Filters

| # | Initiative | Category | Priority | Impact |
|---|-----------|----------|----------|--------|
| 9 | ✅ [IP Allowlist / Denylist Filter](#9-ip-allowlist--denylist-filter) | Security | High | Blocks/allows requests by client IP or CIDR range before any other filter executes |
| 10 | [Geographic Routing Filter](#10-geographic-routing-filter) | Routing | Medium | Routes requests to geographically closest upstream based on MaxMind GeoIP |
| 11 | [Request Size Limit Filter](#11-request-size-limit-filter) | Validation | High | Enforces per-route request body size limits with early rejection |
| 12 | [Response Cache Filter](#12-response-cache-filter) | Performance | High | Per-route Redis-backed response caching with configurable TTL and cache-control semantics |
| 13 | [Circuit Breaker v2 Filter](#13-circuit-breaker-v2-filter) | Resilience | High | Custom Resilience4j circuit breaker with per-route config, half-open probing, and dashboard status |
| 14 | [Retry v2 Filter](#14-retry-v2-filter) | Resilience | High | Custom retry filter with exponential backoff, jitter, idempotency awareness, and per-route config |
| 15 | [Request/Response Body Size Logging Filter](#15-requestresponse-body-size-logging-filter) | Observability | Low | Lightweight filter that emits Micrometer metrics for body sizes without capturing content |
| 16 | [OAuth2 Token Relay Filter](#16-oauth2-token-relay-filter) | Authentication | Medium | Exchange incoming token for a downstream-specific token via token exchange (RFC 8693) |
| 17 | [GraphQL Depth Limit Filter](#17-graphql-depth-limit-filter) | Validation | Medium | Parses GraphQL queries and rejects those exceeding configurable depth/complexity limits |
| 18 | [Response Header Rewrite Filter](#18-response-header-rewrite-filter) | Modification | Medium | Regex-based response header value rewriting (e.g., rewrite `Location` headers for proxy URLs) |
| 19 | [Idempotency Key Filter](#19-idempotency-key-filter) | Reliability | High | Deduplicates write requests using a client-provided idempotency key stored in Redis |
| 20 | [Request Decompression Filter](#20-request-decompression-filter) | Performance | Medium | Transparently decompresses `gzip`/`br`/`zstd` request bodies before forwarding upstream |
| 21 | [Mock Response Filter](#21-mock-response-filter) | Developer Experience | Medium | Returns a configurable static JSON/XML response without forwarding to upstream — enables API stubbing |
| 22 | [Webhook Notification Filter](#22-webhook-notification-filter) | Integration | Medium | Fires a non-blocking webhook POST on configurable request conditions (status code, header match) |

---

### 9. IP Allowlist / Denylist Filter

**Design doc:** [`initiatives/gf-09-ip-access-control.md`](./initiatives/gf-09-ip-access-control.md)  
**Filter type:** `IP_ACCESS_CONTROL`  
**Category:** Security  
**Priority:** High

#### Description
A high-priority, order-first filter that evaluates the client IP address against configurable allowlists and denylists. Supports individual IPs, CIDR ranges (IPv4 and IPv6), and X-Forwarded-For header parsing for deployments behind load balancers. This is the most commonly requested missing filter for enterprise deployments.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `mode` | `ALLOWLIST` \| `DENYLIST` | `DENYLIST` | Whether the list is an allowlist (only listed IPs pass) or denylist (listed IPs blocked) |
| `addresses` | `String[]` | `[]` | IP addresses and CIDR ranges (e.g., `10.0.0.0/8`, `192.168.1.100`, `::1`) |
| `trustProxy` | `boolean` | `true` | Whether to resolve client IP from `X-Forwarded-For` header |
| `proxyDepth` | `int` | `1` | Which `X-Forwarded-For` entry to use (1 = rightmost proxy hop) |
| `rejectStatus` | `int` | `403` | HTTP status code for rejected requests |
| `rejectMessage` | `String` | `"Access denied"` | Error detail in the ProblemDetail response |

#### Implementation Plan
1. **Create `IpAccessControlGatewayFilterFactory`** with `Ordered` interface (order `-1500`, before all auth filters).
2. **CIDR matching** — use `java.net.InetAddress` for parsing and a custom `CidrMatcher` for prefix matching. Compile CIDR ranges once at config bind time, not per-request.
3. **IPv6 support** — normalize IPv4-mapped IPv6 addresses (`::ffff:192.168.1.1` → `192.168.1.1`) for consistent matching.
4. **Hot-reload** — CIDR list changes propagate via the existing Kafka event → route reload pipeline without gateway restart.
5. **Metrics** — increment `routify.filter.ip_access_control.{allowed,blocked}` counters per route.

---

### 10. Geographic Routing Filter

**Design doc:** [`initiatives/gf-10-geo-routing.md`](./initiatives/gf-10-geo-routing.md)  
**Filter type:** `GEO_ROUTE`  
**Category:** Routing  
**Priority:** Medium

#### Description
Routes requests to geographically closest upstream endpoints using MaxMind GeoIP2 database lookups. Enables multi-region deployments where the gateway selects the nearest backend cluster based on the caller's IP geolocation.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `regions` | `Map<String, String>` | `{}` | Region code → upstream URI mapping (e.g., `{"EU": "https://eu.api.example.com", "US": "https://us.api.example.com"}`) |
| `defaultRegion` | `String` | `"US"` | Fallback region when GeoIP lookup fails or is inconclusive |
| `geoDbPath` | `String` | `classpath:GeoLite2-Country.mmdb` | Path to MaxMind GeoLite2 database file |
| `cacheSize` | `int` | `10000` | In-memory LRU cache size for IP → country lookups |

#### Implementation Plan
1. **Add `com.maxmind.geoip2:geoip2` dependency** to `routify-api-gateway/pom.xml`.
2. **Create `GeoRouteGatewayFilterFactory`** — resolve client IP → country code → region → upstream URI.
3. **LRU cache** — Caffeine in-process cache for IP → region mappings (avoid disk I/O per request).
4. **Fallback chain** — if GeoIP lookup fails → use `defaultRegion`; if `defaultRegion` not in `regions` → pass through to route's original upstream.
5. **`X-Geo-Region` header** — inject the resolved region code for downstream observability.

---

### 11. Request Size Limit Filter

**Design doc:** [`initiatives/gf-11-request-size-limit.md`](./initiatives/gf-11-request-size-limit.md)  
**Filter type:** `REQUEST_SIZE_LIMIT`  
**Category:** Validation  
**Priority:** High

#### Description
Enforces a per-route maximum request body size, rejecting oversized payloads early (before body buffering) with HTTP 413 Payload Too Large. Unlike the deprecated `VALIDATE_SIZE` which used SCG's built-in `RequestSize` filter, this custom implementation supports configurable error responses, tenant-aware limits, and Micrometer metrics.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxSize` | `String` | `5MB` | Maximum request body size (supports `KB`, `MB`, `GB` suffixes) |
| `checkContentLength` | `boolean` | `true` | Reject based on `Content-Length` header (fast path, before reading body) |
| `checkActualSize` | `boolean` | `true` | Also enforce limit by counting actual body bytes (for chunked transfers) |
| `tenantAware` | `boolean` | `false` | When true, resolve per-tenant size limits from `TenantPlan` quotas |

#### Implementation Plan
1. **Create `RequestSizeLimitGatewayFilterFactory`** with two enforcement stages:
   - **Stage 1 (header-based):** Check `Content-Length` header; reject immediately if over limit.
   - **Stage 2 (streaming):** Wrap body with a `DataBuffer` counter that rejects mid-stream if cumulative bytes exceed limit.
2. **Tenant-aware mode** — read tenant plan from the resolved `X-Tenant-Id` header and apply plan-specific limits.
3. **RFC 9457 response** — `413 Payload Too Large` with `maxSize` in the ProblemDetail body.
4. **Metrics** — `routify.filter.request_size.rejected` counter; `routify.filter.request_size.bytes` distribution summary.

---

### 12. Response Cache Filter

**Design doc:** [`initiatives/gf-12-response-cache.md`](./initiatives/gf-12-response-cache.md)  
**Filter type:** `RESPONSE_CACHE`  
**Category:** Performance  
**Priority:** High

#### Description
Per-route, Redis-backed response caching with configurable TTL, HTTP cache-control header semantics, and cache key strategies. Dramatically reduces upstream load for cacheable GET endpoints. Integrates with the dashboard for cache invalidation controls.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `ttlSeconds` | `int` | `60` | Default cache TTL |
| `maxCachedBodySize` | `int` | `65536` | Max response body bytes to cache (64 KB) |
| `methods` | `String` | `GET` | HTTP methods to cache (comma-separated) |
| `statusCodes` | `String` | `200,206,301` | Status codes eligible for caching |
| `keyStrategy` | `String` | `PATH_QUERY` | Cache key: `PATH_QUERY` \| `PATH_QUERY_HEADERS` \| `CUSTOM_SPEL` |
| `varyHeaders` | `String[]` | `[]` | Headers to include in cache key (when `keyStrategy=PATH_QUERY_HEADERS`) |
| `respectCacheControl` | `boolean` | `true` | Honour upstream `Cache-Control: no-cache`, `no-store`, `max-age` |
| `addCacheHeaders` | `boolean` | `true` | Inject `X-Cache: HIT` or `X-Cache: MISS` on responses |

#### Implementation Plan
1. **Create `ResponseCacheGatewayFilterFactory`** — on cache HIT, return the stored response directly without forwarding to upstream. On MISS, forward to upstream, cache the response, then return.
2. **Redis storage** — cache entries stored as Redis Hashes with fields: `status`, `headers` (JSON), `body` (Base64), `cachedAt`, `ttl`.
3. **Cache key** — `routify:cache:{routeId}:{strategy-derived-key}`. SHA-256 hash of the derived key to keep Redis keys bounded.
4. **`Cache-Control` respect** — parse upstream `Cache-Control` header; honour `no-store` (don't cache), `max-age` (use as TTL if shorter than config), `private` (skip caching for tenant-scoped responses).
5. **Cache invalidation** — admin-api endpoint `POST /api/v1/admin/routes/{id}/cache/purge` sends a Kafka command that the gateway consumes to delete all cache keys for a route.
6. **Metrics** — `routify.filter.cache.{hit,miss,skip}` counters; `routify.filter.cache.size_bytes` gauge.

---

### 13. Circuit Breaker v2 Filter

**Design doc:** [`initiatives/gf-13-circuit-breaker-v2.md`](./initiatives/gf-13-circuit-breaker-v2.md)  
**Filter type:** `CIRCUIT_BREAKER_V2`  
**Category:** Resilience  
**Priority:** High

#### Description
A custom circuit breaker filter replacing the deprecated `CIRCUIT_BREAKER` type (which delegated to SCG's built-in `CircuitBreaker` filter with minimal configuration). This version provides per-route Resilience4j `CircuitBreaker` instances with configurable thresholds, slow-call detection, half-open probing, and integration with the dashboard's circuit breaker visualization (via WebSocket `wsStore`).

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `failureRateThreshold` | `float` | `50.0` | Failure rate percentage to trip the circuit (0–100) |
| `slowCallRateThreshold` | `float` | `80.0` | Slow call rate percentage to trip the circuit |
| `slowCallDurationMs` | `long` | `3000` | Threshold in ms above which a call is considered slow |
| `slidingWindowSize` | `int` | `10` | Number of calls in the sliding window |
| `slidingWindowType` | `String` | `COUNT_BASED` | `COUNT_BASED` or `TIME_BASED` |
| `minimumNumberOfCalls` | `int` | `5` | Minimum calls before circuit evaluates failure rate |
| `waitDurationInOpenStateMs` | `long` | `60000` | Time in open state before transitioning to half-open |
| `permittedNumberOfCallsInHalfOpenState` | `int` | `3` | Calls allowed in half-open for probing |
| `fallbackStatus` | `int` | `503` | HTTP status when circuit is open |
| `fallbackBody` | `String` | (ProblemDetail JSON) | Response body when circuit is open |

#### Implementation Plan
1. **Create `CircuitBreakerV2GatewayFilterFactory`** — create a `CircuitBreaker` instance per route (keyed by `routeId`).
2. **Reactive integration** — use Resilience4j's `ReactorResilience4j.decorateMono()` to wrap `chain.filter()`.
3. **State broadcast** — on state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED), publish to the WebSocket `/topic/events` stream so the dashboard updates the circuit breaker visualization in real time.
4. **Metrics** — Resilience4j auto-registers Micrometer metrics (`resilience4j.circuitbreaker.*`); tag with `routeId` for per-route visibility.
5. **Manual override** — admin-api endpoint `POST /api/v1/admin/routes/{id}/circuit-breaker/force-{open,closed,reset}` sends a Kafka command consumed by the gateway.

---

### 14. Retry v2 Filter

**Design doc:** [`initiatives/gf-14-retry-v2.md`](./initiatives/gf-14-retry-v2.md)  
**Filter type:** `RETRY_V2`  
**Category:** Resilience  
**Priority:** High

#### Description
A custom retry filter replacing the deprecated `RETRY` type. Adds exponential backoff with jitter, idempotency-aware retry logic (only retry safe methods or requests with an idempotency key), and configurable retry conditions beyond just HTTP status codes.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxRetries` | `int` | `3` | Maximum retry attempts |
| `initialBackoffMs` | `long` | `500` | Initial backoff delay |
| `maxBackoffMs` | `long` | `5000` | Maximum backoff delay |
| `backoffMultiplier` | `double` | `2.0` | Backoff multiplier |
| `jitterFactor` | `double` | `0.25` | Random jitter factor (0.0–1.0) |
| `retryableStatuses` | `String` | `502,503,504` | HTTP status codes to retry on |
| `retryableMethods` | `String` | `GET,HEAD,OPTIONS` | HTTP methods safe to retry |
| `retryOnTimeout` | `boolean` | `true` | Retry on upstream connection/read timeouts |
| `idempotencyHeader` | `String` | `Idempotency-Key` | When present, any method is retried (client guarantees idempotency) |

#### Implementation Plan
1. **Create `RetryV2GatewayFilterFactory`** — implement a reactive retry loop using Reactor `retryWhen(Retry.backoff(...))`.
2. **Idempotency awareness** — when `Idempotency-Key` header is present, allow retries even for POST/PUT/PATCH (the client guarantees idempotency).
3. **Jitter** — add `ThreadLocalRandom`-based jitter to prevent retry storms across concurrent clients.
4. **Retry header** — inject `X-Retry-Count: <n>` header on retried requests for upstream observability.
5. **Metrics** — `routify.filter.retry.{attempt,exhausted,success}` counters tagged by `routeId`.

---

### 15. Request/Response Body Size Logging Filter

**Design doc:** [`initiatives/gf-15-body-size-metric.md`](./initiatives/gf-15-body-size-metric.md)  
**Filter type:** `BODY_SIZE_METRIC`  
**Category:** Observability  
**Priority:** Low

#### Description
A lightweight, zero-copy filter that records request and response body sizes as Micrometer distribution summaries without reading or buffering the body content. Useful for monitoring payload size trends and detecting anomalies.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `includeRequest` | `boolean` | `true` | Record request body size |
| `includeResponse` | `boolean` | `true` | Record response body size |
| `tags` | `Map<String, String>` | `{}` | Additional Micrometer tags |

#### Implementation Plan
1. **Create `BodySizeMetricGatewayFilterFactory`** — read `Content-Length` headers (not body streams).
2. **Metrics** — `routify.request.body.size` and `routify.response.body.size` distribution summaries tagged with `routeId`, `method`, `status`.
3. **Chunked transfers** — when `Content-Length` is absent, wrap the body `Flux<DataBuffer>` with a non-buffering counter that sums `readableByteCount()` per chunk.

---

### 16. OAuth2 Token Relay Filter

**Design doc:** [`initiatives/gf-16-oauth2-token-relay.md`](./initiatives/gf-16-oauth2-token-relay.md)  
**Filter type:** `OAUTH2_TOKEN_RELAY`  
**Category:** Authentication  
**Priority:** Medium

#### Description
Implements RFC 8693 Token Exchange — exchanges the incoming bearer token for a downstream-specific token before forwarding the request. Enables the gateway to act as a token translation layer between different identity providers or token scopes.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `tokenEndpoint` | `String` | (required) | OAuth2 token endpoint URL |
| `clientId` | `String` | (required) | Client ID for token exchange |
| `clientSecret` | `String` | (required) | Client secret (sensitive, masked) |
| `subjectTokenType` | `String` | `urn:ietf:params:oauth:token-type:access_token` | Subject token type |
| `requestedTokenType` | `String` | `urn:ietf:params:oauth:token-type:access_token` | Requested token type |
| `scope` | `String` | `""` | Scopes to request for the exchanged token |
| `audience` | `String` | `""` | Target audience for the exchanged token |
| `cacheTtlSeconds` | `int` | `300` | Cache exchanged tokens to avoid per-request exchange |

#### Implementation Plan
1. **Create `OAuth2TokenRelayGatewayFilterFactory`** — extract incoming bearer token, exchange via `WebClient` call to token endpoint, inject exchanged token as `Authorization: Bearer <exchanged>` for upstream.
2. **Token cache** — Caffeine in-process cache keyed by incoming token hash + audience, with TTL = `min(cacheTtlSeconds, token.expires_in - 30s)`.
3. **Reactive WebClient** — all token exchange calls are fully non-blocking.
4. **Fallback** — on exchange failure, configurable: `REJECT` (401), `PASS_THROUGH` (forward original token), `STRIP` (remove Authorization header).

---

### 17. GraphQL Depth Limit Filter

**Design doc:** [`initiatives/gf-17-graphql-depth-limit.md`](./initiatives/gf-17-graphql-depth-limit.md)  
**Filter type:** `GRAPHQL_DEPTH_LIMIT`  
**Category:** Validation  
**Priority:** Medium

#### Description
Parses incoming GraphQL query documents and rejects queries that exceed configurable depth or complexity limits. Prevents abuse of deeply nested queries that can cause exponential backend processing.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxDepth` | `int` | `10` | Maximum allowed query depth |
| `maxComplexity` | `int` | `100` | Maximum query complexity score (each field = 1, list field = multiplier) |
| `maxAliases` | `int` | `5` | Maximum number of aliases per query |
| `introspectionAllowed` | `boolean` | `false` | Whether `__schema` / `__type` introspection queries are allowed |
| `maxBatchSize` | `int` | `5` | Maximum number of operations in a batched query |

#### Implementation Plan
1. **Add `graphql-java` dependency** (parser only, no execution engine) to `routify-api-gateway/pom.xml`.
2. **Create `GraphQLDepthLimitGatewayFilterFactory`** — read the request body, parse the `query` field, walk the AST to compute depth and complexity.
3. **Depth calculation** — recursive AST traversal; each `Field` node increments depth by 1; inline fragments and named fragments are followed.
4. **Complexity scoring** — configurable cost per field type (default: 1 per field, configurable multiplier for connection/list fields).
5. **Batch query** — if the body is a JSON array, enforce `maxBatchSize` and evaluate each operation independently.
6. **Non-GraphQL requests** — pass through without parsing (detect via `Content-Type` or path pattern).

---

### 18. Response Header Rewrite Filter

**Design doc:** [`initiatives/gf-18-response-header-rewrite.md`](./initiatives/gf-18-response-header-rewrite.md)  
**Filter type:** `RESPONSE_HEADER_REWRITE`  
**Category:** Modification  
**Priority:** Medium

#### Description
Performs regex-based response header value rewriting. Primary use case: rewriting `Location` redirect headers from upstream internal URLs to external proxy-facing URLs, and rewriting `Set-Cookie` domain attributes.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `headerName` | `String` | (required) | Response header to rewrite |
| `pattern` | `String` | (required) | Java regex pattern to match against the header value |
| `replacement` | `String` | (required) | Replacement string (supports `$1`, `$2` capture group references) |
| `replaceAll` | `boolean` | `false` | Whether to replace all occurrences or just the first |

#### Implementation Plan
1. **Create `ResponseHeaderRewriteGatewayFilterFactory`** — wrap response in a `ServerHttpResponseDecorator` whose `getHeaders()` method lazily rewrites matched headers.
2. **Pre-compile regex** at config bind time for performance.
3. **Multi-header support** — if multiple values exist for the header, rewrite each independently.
4. **Common presets** — document common configurations: internal-to-external URL rewrite, `Set-Cookie` domain rewrite, CORS `Access-Control-Allow-Origin` normalization.

---

### 19. Idempotency Key Filter

**Design doc:** [`initiatives/gf-19-idempotency-key.md`](./initiatives/gf-19-idempotency-key.md)  
**Filter type:** `IDEMPOTENCY_KEY`  
**Category:** Reliability  
**Priority:** High

#### Description
Deduplicates write requests using a client-provided idempotency key (typically `Idempotency-Key` header per the emerging IETF standard). On first request: execute and cache the response in Redis. On replay: return the cached response without forwarding to upstream.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `headerName` | `String` | `Idempotency-Key` | Header carrying the idempotency key |
| `ttlSeconds` | `int` | `86400` | How long to remember processed keys (24 hours) |
| `methods` | `String` | `POST,PUT,PATCH` | HTTP methods to enforce idempotency on |
| `requireHeader` | `boolean` | `false` | Reject requests without the idempotency header (409 Conflict) |
| `maxCachedBodySize` | `int` | `65536` | Max response body bytes to cache |

#### Implementation Plan
1. **Create `IdempotencyKeyGatewayFilterFactory`** — check Redis for `routify:idempotency:{routeId}:{key}`.
2. **First request** — set a Redis lock (`NX` + TTL) with status `PROCESSING`. Forward to upstream. On response, cache `{status, headers, body}` and update lock to `COMPLETE`.
3. **Replay** — if key exists and status is `COMPLETE`, return cached response. If `PROCESSING`, return `409 Conflict` (concurrent duplicate).
4. **Response decorator** — capture the response body using `ServerHttpResponseDecorator.writeWith()` to cache it in Redis after writing to the client.
5. **`Idempotency-Key-Status`** response header — `HIT` (cached response) or `MISS` (first execution).

---

### 20. Request Decompression Filter

**Design doc:** [`initiatives/gf-20-request-decompression.md`](./initiatives/gf-20-request-decompression.md)  
**Filter type:** `REQUEST_DECOMPRESS`  
**Category:** Performance  
**Priority:** Medium

#### Description
Transparently decompresses `gzip`, `br` (Brotli), and `zstd` encoded request bodies before forwarding to upstream services that don't support compressed payloads. Useful when mobile/IoT clients compress payloads to reduce bandwidth but backend services expect uncompressed JSON.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `supportedEncodings` | `String` | `gzip,br,zstd` | Content-Encoding types to decompress |
| `maxDecompressedSize` | `String` | `10MB` | Maximum decompressed body size (zip bomb protection) |
| `removeEncoding` | `boolean` | `true` | Remove `Content-Encoding` header after decompression |
| `updateContentLength` | `boolean` | `true` | Update `Content-Length` to reflect decompressed size |

#### Implementation Plan
1. **Create `RequestDecompressGatewayFilterFactory`** — check `Content-Encoding` header; if it matches a supported encoding, decompress the body stream.
2. **`gzip`** — use `java.util.zip.GZIPInputStream` wrapped in a reactive `DataBuffer` pipeline.
3. **`br` (Brotli)** — use `org.brotli:dec` library dependency.
4. **`zstd`** — use `com.github.luben:zstd-jni` library dependency.
5. **Zip bomb protection** — track decompressed bytes; abort with 413 if `maxDecompressedSize` is exceeded.
6. **Header cleanup** — remove `Content-Encoding`, update `Content-Length`, add `X-Original-Encoding: gzip`.

---

### 21. Mock Response Filter

**Design doc:** [`initiatives/gf-21-mock-response.md`](./initiatives/gf-21-mock-response.md)  
**Filter type:** `MOCK_RESPONSE`  
**Category:** Developer Experience  
**Priority:** Medium

#### Description
Returns a configurable static response without forwarding the request to any upstream service. Enables API stubbing, contract-first development, and maintenance mode pages directly at the gateway level.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `status` | `int` | `200` | HTTP status code to return |
| `contentType` | `String` | `application/json` | Response `Content-Type` header |
| `body` | `String` | `{}` | Response body (supports string template interpolation with request attributes) |
| `headers` | `Map<String, String>` | `{}` | Additional response headers |
| `delay` | `long` | `0` | Simulated latency in milliseconds (useful for testing client timeout handling) |
| `conditionHeader` | `String` | (optional) | Only mock when this header is present (allows toggling mock per-request) |

#### Implementation Plan
1. **Create `MockResponseGatewayFilterFactory`** — short-circuit the filter chain; write the configured response directly.
2. **Template interpolation** — support `${method}`, `${path}`, `${header:X-Foo}`, `${param:id}` placeholders in the `body` template.
3. **Delay simulation** — use `Mono.delay(Duration.ofMillis(config.delay))` before writing the response.
4. **Conditional activation** — when `conditionHeader` is set, only return mock when the header is present; otherwise pass through to upstream.
5. **Maintenance mode** — document a pattern for maintenance mode: `MOCK_RESPONSE` filter with `status=503`, `body={"message":"Service under maintenance"}`, applied to all routes via global filter entry.

---

### 22. Webhook Notification Filter

**Design doc:** [`initiatives/gf-22-webhook-notify.md`](./initiatives/gf-22-webhook-notify.md)  
**Filter type:** `WEBHOOK_NOTIFY`  
**Category:** Integration  
**Priority:** Medium

#### Description
Fires a non-blocking webhook HTTP POST when a request matches configurable conditions (e.g., specific status codes, header presence, error responses). Unlike the platform-level webhook system (which reacts to domain events), this filter operates at the request level — useful for real-time alerting on specific traffic patterns.

#### Config Params
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `webhookUrl` | `String` | (required) | URL to POST the notification to |
| `secret` | `String` | (optional) | HMAC-SHA256 signing key for `X-Routify-Signature` header |
| `triggerOn` | `String` | `5xx` | Condition: `5xx`, `4xx`, `ALL`, or specific status codes (comma-separated) |
| `headerMatch` | `String` | (optional) | Only trigger when a specific header value matches (e.g., `X-AI-Filter-Flag=true`) |
| `includeRequestHeaders` | `boolean` | `false` | Include sanitized request headers in the webhook payload |
| `includeResponseStatus` | `boolean` | `true` | Include response status code in the webhook payload |
| `maxPayloadSize` | `int` | `4096` | Max webhook payload size |

#### Implementation Plan
1. **Create `WebhookNotifyGatewayFilterFactory`** — evaluate conditions in `doOnEach()` after the upstream response arrives.
2. **Non-blocking dispatch** — use `WebClient` to POST the notification on `Schedulers.boundedElastic()`. Fire-and-forget; never block the response to the client.
3. **HMAC signing** — reuse the same `X-Routify-Signature` pattern from the platform webhook system.
4. **Payload** — JSON: `{ "routeId", "correlationId", "method", "path", "status", "elapsedMs", "timestamp" }` plus optional request headers.
5. **Rate limiting** — max 1 webhook per route per 10 seconds to prevent notification storms (configurable `cooldownSeconds`).

---

## `FilterType` Enum Additions Summary

The following values should be added to `FilterType.java` and the TypeScript `FilterType` union when each initiative is implemented:

```java
// ─── Security ─────────────────────────────────────────────────────────────
IP_ACCESS_CONTROL,

// ─── Routing ──────────────────────────────────────────────────────────────
GEO_ROUTE,

// ─── Validation ───────────────────────────────────────────────────────────
REQUEST_SIZE_LIMIT,
GRAPHQL_DEPTH_LIMIT,

// ─── Performance ──────────────────────────────────────────────────────────
RESPONSE_CACHE,
REQUEST_DECOMPRESS,

// ─── Resilience ───────────────────────────────────────────────────────────
CIRCUIT_BREAKER_V2,
RETRY_V2,
IDEMPOTENCY_KEY,

// ─── Authentication ───────────────────────────────────────────────────────
OAUTH2_TOKEN_RELAY,

// ─── Modification ─────────────────────────────────────────────────────────
RESPONSE_HEADER_REWRITE,

// ─── Observability ────────────────────────────────────────────────────────
BODY_SIZE_METRIC,

// ─── Developer Experience ─────────────────────────────────────────────────
MOCK_RESPONSE,

// ─── Integration ──────────────────────────────────────────────────────────
WEBHOOK_NOTIFY,
```

---

## Dependency & Sequencing Map

Initiatives are grouped into implementation waves based on priority and dependencies.

### Wave 1 — Foundation (Weeks 1–4)
Cross-cutting refactors that unblock all subsequent work.

```
█████████████████  1. Extract Shared Key Resolver
█████████████████████████████████████  2. Unified Error Response Builder
████████████████████████████  7. SpEL Filter Sandboxing
```

### Wave 2 — High-Priority Filters (Weeks 3–8)
Most-requested new filters and critical improvements.

```
         █████████████████████████████████████  4. JwtAuth Hardening
         ████████████████████████████  9. IP Allowlist/Denylist
                  █████████████████████████████  11. Request Size Limit
                  █████████████████████████████  12. Response Cache
                  █████████████████████████████  13. Circuit Breaker v2
```

### Wave 3 — Resilience & Performance (Weeks 6–10)
Build on Wave 2's error response builder and key resolver.

```
                           █████████████████████████████  3. Rate Limiter Headers
                           █████████████████████████████  14. Retry v2
                           █████████████████████████████  19. Idempotency Key
                                    ████████████████████  20. Request Decompression
```

### Wave 4 — Advanced Features (Weeks 8–12)
Specialised filters for specific use cases.

```
                                             ████████████████████  5. Jolt Response Phase
                                             ████████████████████  6. RequestLogger Improvements
                                             ████████████████████  8. AI Filter Streaming Body
                                             ████████████████████  10. Geographic Routing
```

### Wave 5 — Extensions (Weeks 10–14)
Nice-to-have filters that round out the platform.

```
                                                          █████████████████████████████  15. Body Size Metric
                                                          █████████████████████████████  16. OAuth2 Token Relay
                                                          █████████████████████████████  17. GraphQL Depth Limit
                                                          █████████████████████████████  18. Response Header Rewrite
                                                          █████████████████████████████  21. Mock Response
                                                          █████████████████████████████  22. Webhook Notify
```

### Key Dependencies

| Initiative | Depends On |
|-----------|------------|
| 3. Rate Limiter Headers | 1. Shared Key Resolver, 2. Error Response Builder |
| 9. IP Access Control | 2. Error Response Builder |
| 11. Request Size Limit | 2. Error Response Builder |
| 12. Response Cache | (standalone) |
| 13. Circuit Breaker v2 | 2. Error Response Builder, Q3-05 (Health Dashboard v2 for CB visualization) |
| 14. Retry v2 | 19. Idempotency Key (for idempotency header awareness) |
| 17. GraphQL Depth Limit | 2. Error Response Builder |
| 19. Idempotency Key | 12. Response Cache (shared Redis caching patterns) |
| 22. Webhook Notify | Q3-03 (Webhook Notification System for HMAC signing reuse) |

---

## Dashboard Integration Plan

Each new filter type requires corresponding dashboard changes:

1. **Filter type selector** — add to the filter create/edit form's `filterType` dropdown in `src/modules/filters/`.
2. **Config form schema** — Zod schema for each filter's config params; React Hook Form renders the appropriate fields.
3. **TypeScript types** — add the new `FilterType` values to the `FilterType` union in `src/types/index.ts`.
4. **Filter documentation** — each filter type should have an inline help tooltip with description, config params, and example configurations.
5. **Workflow builder** — new filter nodes in `src/modules/workflow-builder/` with category-appropriate icons and color coding.

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Response Cache stale data in multi-gateway deployments | Medium | High | Use Kafka event-based cache invalidation; add `Cache-Control: no-cache` bypass |
| SpEL sandboxing breaks existing expressions that rely on `#request` | Medium | Medium | Migration window: log warnings for 2 releases before removing `#request`; provide `#clientIp` and `#contentType` as replacements |
| GeoIP database licensing (MaxMind GeoLite2) requires registration | Low | Low | Document MaxMind account signup; support `GeoIP2-Country.mmdb` from any provider |
| Request decompression zip bombs bypass `maxDecompressedSize` check | Low | High | Stream-based byte counting with hard abort; never buffer entire decompressed payload in memory |
| Circuit Breaker v2 state not shared across gateway instances | Medium | Medium | Q4 Multi-Gateway Cluster (Init 3) adds Redis-shared CB state; until then, each instance has independent CBs |
| Idempotency Key filter Redis storage grows unbounded | Low | Medium | TTL-based expiry (default 24h); Redis memory monitoring alert |
| GraphQL parser adds startup latency from `graphql-java` | Low | Low | Lazy-initialise parser on first GraphQL request; exclude from health check |
| Response Header Rewrite regex catastrophic backtracking | Medium | Medium | Use `Pattern.compile()` at config time with a timeout; reject pathological regexes |

---

## Success Metrics

| Metric | Baseline | Target |
|--------|----------|--------|
| Duplicated code lines across rate limit filters | ~50 lines × 2 | 0 (shared `RateLimitKeyResolver`) |
| Filter factories with inconsistent error response format | 12 | 0 (all use `GatewayProblemResponse`) |
| Rate limiter responses with `X-RateLimit-*` headers | 0% | 100% |
| JWT auth without signature verification (dev mode) | Possible | Impossible (fail-closed) |
| SpEL expressions with access to `ClassLoader` | Possible | Impossible (sandboxed context) |
| Response cache hit rate (cacheable GET routes) | 0% (no cache) | >60% typical |
| Gateway filter types available | 28 active | 42 active (+14 new) |
| Filters with full unit test coverage | ~10/30 | 42/42 (100%) |
| Avg lines of error response code per filter | ~12 | ~1 (delegated to `GatewayProblemResponse`) |
| P99 latency overhead per filter (excluding upstream) | Unmeasured | < 2 ms per filter (benchmarked) |

---

## Appendix: Current Filter Inventory

For reference, the complete list of active (non-deprecated) gateway filters as of platform version 2.0.x:

| # | FilterType | Factory Class | Category |
|---|-----------|---------------|----------|
| 1 | `AUTH_API_KEY` | `ApiKeyAuthGatewayFilterFactory` | Authentication |
| 2 | `AUTH_BASIC` | `BasicAuthGatewayFilterFactory` | Authentication |
| 3 | `AUTH_JWT` | `JwtAuthGatewayFilterFactory` | Authentication |
| 4 | `AUTH_MTLS` | `MtlsAuthGatewayFilterFactory` | Authentication |
| 5 | `AUTH_OAUTH2` | `OAuth2TokenIntrospectGatewayFilterFactory` | Authentication |
| 6 | `AUTH_CLIENT_ID` | `ClientIdAuthGatewayFilterFactory` | Authentication |
| 7 | `AUTH_CERT_VAULT` | `CertVaultAuthGatewayFilterFactory` | Authentication |
| 8 | `DOWNSTREAM_BASIC_AUTH` | `DownstreamBasicAuthGatewayFilterFactory` | Downstream Auth |
| 9 | `DOWNSTREAM_BEARER_CC` | `DownstreamOAuth2BearerGatewayFilterFactory` | Downstream Auth |
| 10 | `RATE_LIMIT_FIXED_WINDOW` | `FixedWindowRateLimitGatewayFilterFactory` | Rate Limiting |
| 11 | `RATE_LIMIT_SLIDING_WINDOW` | `SlidingWindowRateLimitGatewayFilterFactory` | Rate Limiting |
| 12 | `REQUEST_HEADER_MODIFY` | `RequestHeaderModifyGatewayFilterFactory` | Modification |
| 13 | `RESPONSE_HEADER_MODIFY` | `ResponseHeaderModifyGatewayFilterFactory` | Modification |
| 14 | `BODY_JOLT_TRANSFORM` | `JoltTransformGatewayFilterFactory` | Body Transform |
| 15 | `VALIDATE_JSON_SCHEMA` | `JsonSchemaValidateGatewayFilterFactory` | Validation |
| 16 | `TIMEOUT` | `RequestTimeoutGatewayFilterFactory` | Resilience |
| 17 | `CONDITIONAL_ROUTE` | `ConditionalRouteGatewayFilterFactory` | Routing |
| 18 | `USER_ID_PAYLOAD_ROUTING` | `UserIdPayloadRoutingGatewayFilterFactory` | Routing |
| 19 | `CERT_ROTATION` | `CertRotationGatewayFilterFactory` | Certificate |
| 20 | `CERT_VAULT_EXPIRY_CHECK` | `CertVaultExpiryCheckGatewayFilterFactory` | Certificate |
| 21 | `API_VERSIONING` | `ApiVersioningGatewayFilterFactory` | Versioning |
| 22 | `CORRELATION_ID` | `CorrelationIdGatewayFilterFactory` | Observability |
| 23 | `REQUEST_LOGGER` | `RequestLoggerGatewayFilterFactory` | Observability |
| 24 | `TENANT_CONTEXT` | `TenantContextGatewayFilterFactory` | Observability |
| 25 | `SECURITY_HEADERS` | `SecurityHeadersGatewayFilterFactory` | Observability |
| 26 | `CUSTOM_METRIC` | `CustomMetricGatewayFilterFactory` | Observability |
| 27 | `CUSTOM_SPEL` | `SpelCustomGatewayFilterFactory` | Custom |
| 28 | `AI_FILTER` | `AiGatewayFilterFactory` | AI |
| 29 | `AI_MODIFIER` | `AiModifierGatewayFilterFactory` | AI |

