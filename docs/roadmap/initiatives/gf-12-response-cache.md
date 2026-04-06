# Initiative GF-12 — Response Cache Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 2 (High-Priority Filters) · **Owner:** Gateway team  
> **Category:** Performance · **Priority:** High  
> **Filter type:** `RESPONSE_CACHE`

---

## Problem Statement

Every GET request is forwarded to the upstream service, even for responses that are identical across requests. There is no gateway-level caching, which means cacheable endpoints generate unnecessary upstream load. Operators must implement caching at the upstream service or CDN level, losing visibility in the Routify dashboard.

## Solution Overview

A per-route, Redis-backed response cache with configurable TTL, HTTP `Cache-Control` header semantics, multiple cache key strategies, and a dashboard-accessible cache invalidation endpoint.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/performance/ResponseCacheGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `ttlSeconds` | `int` | `60` | Default cache TTL |
| `maxCachedBodySize` | `int` | `65536` | Max response body bytes to cache (64 KB) |
| `methods` | `String` | `GET` | HTTP methods to cache |
| `statusCodes` | `String` | `200,206,301` | Cacheable status codes |
| `keyStrategy` | `String` | `PATH_QUERY` | Cache key strategy |
| `varyHeaders` | `String[]` | `[]` | Headers to include in cache key |
| `respectCacheControl` | `boolean` | `true` | Honour upstream `Cache-Control` |
| `addCacheHeaders` | `boolean` | `true` | Inject `X-Cache: HIT/MISS` |

**Cache key strategies:**
- `PATH_QUERY` — `routeId + path + sorted query params`
- `PATH_QUERY_HEADERS` — above + specified `varyHeaders` values
- `CUSTOM_SPEL` — SpEL expression for custom key derivation

**Task list:**
- [ ] Create filter factory with config binding
- [ ] Implement cache key derivation for all 3 strategies
- [ ] SHA-256 hash the derived key to keep Redis keys bounded

---

### Step 2: Redis Cache Storage

**Redis key format:**
```
routify:cache:{routeId}:{sha256(derivedKey)}
```

**Redis Hash fields:**
- `status` — HTTP status code (int)
- `headers` — JSON serialized response headers
- `body` — Base64 encoded response body
- `cachedAt` — epoch millis
- `ttl` — TTL in seconds

**Implementation:**
- **Cache HIT:** Read from Redis, deserialize, return directly without forwarding to upstream.
- **Cache MISS:** Forward to upstream, capture response via `ServerHttpResponseDecorator.writeWith()`, serialize and store in Redis with TTL, then return to client.
- Body exceeding `maxCachedBodySize` → skip caching (serve response normally, don't store).

**Task list:**
- [ ] Implement Redis read for cache HIT
- [ ] Implement `ServerHttpResponseDecorator` for cache MISS capture
- [ ] Base64 encode/decode response body
- [ ] Set Redis TTL on cache entries
- [ ] Skip caching for oversized bodies

---

### Step 3: `Cache-Control` Header Respect

**Implementation:**
Parse upstream response `Cache-Control` header:
- `no-store` → do not cache this response
- `no-cache` → cache but require revalidation (treat as TTL=0)
- `max-age=N` → use `min(config.ttlSeconds, N)` as TTL
- `private` → skip caching for tenant-scoped responses
- `s-maxage=N` → takes precedence over `max-age` for shared caches

When `respectCacheControl=false`, ignore upstream headers entirely (use configured TTL).

**Task list:**
- [ ] Parse `Cache-Control` header directives
- [ ] Implement `no-store`, `no-cache`, `max-age`, `private`, `s-maxage` handling
- [ ] Honor `respectCacheControl` flag

---

### Step 4: Cache Headers

**On every response:**
- `X-Cache: HIT` or `X-Cache: MISS`
- `X-Cache-TTL: <remaining-seconds>` (on HIT)
- `Age: <seconds-since-cached>` (on HIT, per HTTP/1.1 spec)

When `addCacheHeaders=false`, suppress all cache-related headers.

**Task list:**
- [ ] Inject `X-Cache` header on every response
- [ ] Inject `X-Cache-TTL` and `Age` on cache HITs
- [ ] Support `addCacheHeaders=false`

---

### Step 5: Cache Invalidation Endpoint

**Admin-api endpoint:**
```
POST /api/v1/admin/routes/{id}/cache/purge
```

Dispatches a Kafka command (`CommandEvent.PurgeCacheRoute`) that the gateway consumes to delete all Redis keys matching `routify:cache:{routeId}:*`.

**Task list:**
- [ ] Add `CommandEvent.PurgeCacheRoute` to `routify-common`
- [ ] Add admin-api endpoint
- [ ] Gateway consumer deletes Redis keys on command
- [ ] Dashboard "Purge Cache" button on route detail page

---

### Step 6: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `RESPONSE_CACHE`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [ ] Add `RESPONSE_CACHE` to `FilterType` enum
- [ ] Add to TypeScript `FilterType` union
- [ ] Add filter config form in dashboard

---

### Step 7: Metrics

**Metrics:**
- `routify.filter.cache.hit` — counter, tagged by `routeId`
- `routify.filter.cache.miss` — counter, tagged by `routeId`
- `routify.filter.cache.skip` — counter (body too large, `no-store`, etc.)
- `routify.filter.cache.size_bytes` — gauge of total cached bytes per route

**Task list:**
- [ ] Register Micrometer counters
- [ ] Record hit/miss/skip per request
- [ ] Track cached body size

---

### Step 8: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/performance/ResponseCacheTest.java`

**Test cases:**
- First request → MISS → response cached → second request → HIT
- `X-Cache: MISS` on first request, `X-Cache: HIT` on second
- Cache TTL expiry → next request is MISS
- `Cache-Control: no-store` → response not cached
- `Cache-Control: max-age=30` overrides longer `ttlSeconds`
- Body > `maxCachedBodySize` → served but not cached
- Different query params → different cache keys
- `varyHeaders` included in cache key
- Cache purge deletes all entries for route
- `respectCacheControl=false` ignores upstream headers

**Task list:**
- [ ] Write cache HIT/MISS lifecycle tests
- [ ] Write `Cache-Control` header respect tests
- [ ] Write cache invalidation tests
- [ ] Write metrics validation tests

---

## Acceptance Criteria

- [ ] GET responses cached in Redis with configurable TTL
- [ ] Cache HIT returns stored response without upstream call
- [ ] `X-Cache` header on every response
- [ ] `Cache-Control` directives honored when `respectCacheControl=true`
- [ ] Cache invalidation via admin-api endpoint
- [ ] Body size limit prevents caching oversized responses
- [ ] Micrometer metrics for hit/miss/skip

