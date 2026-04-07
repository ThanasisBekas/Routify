# Initiative GF-10 — Geographic Routing Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 4 (Advanced Features) · **Owner:** Gateway team  
> **Category:** Routing · **Priority:** Medium  
> **Filter type:** `GEO_ROUTE`

---

## Problem Statement

Multi-region deployments require the gateway to route requests to the geographically closest upstream service. Currently, operators must manage this externally via DNS-based routing (e.g., Route53 latency routing), which is opaque to the Routify audit trail and doesn't integrate with per-route configuration.

## Solution Overview

A filter that resolves client IP → country → region using MaxMind GeoIP2 database lookups, then rewrites the upstream URI to the region-appropriate endpoint. Includes an LRU cache for performance and an `X-Geo-Region` header for downstream observability.

---

## Detailed Implementation Steps

### Step 1: Add MaxMind GeoIP2 Dependency

**Files to modify:**
- `routify-api-gateway/pom.xml`

**Dependency:**
```xml
<dependency>
    <groupId>com.maxmind.geoip2</groupId>
    <artifactId>geoip2</artifactId>
    <version>4.2.1</version>
</dependency>
```

**Task list:**
- [x] Add `geoip2` dependency to gateway POM
- [x] Document MaxMind GeoLite2 account signup requirement
- [x] Add `GeoLite2-Country.mmdb` to `.gitignore` (binary file, not committed)

---

### Step 2: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/routing/GeoRouteGatewayFilterFactory.java`
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/routing/GeoIpResolver.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `regions` | `Map<String, String>` | `{}` | Region code → upstream URI mapping |
| `defaultRegion` | `String` | `"US"` | Fallback region |
| `geoDbPath` | `String` | `classpath:GeoLite2-Country.mmdb` | MaxMind database path |
| `cacheSize` | `int` | `10000` | LRU cache size for IP → region lookups |

**Implementation:**
1. `GeoIpResolver` loads the MaxMind database once at startup.
2. On each request: resolve client IP → country code → map to region key.
3. Look up the region's upstream URI from `regions` map.
4. Rewrite the route's upstream URI via `exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, uri)`.
5. Inject `X-Geo-Region: <region>` header for downstream observability.

**Fallback chain:**
- GeoIP lookup fails → use `defaultRegion`
- `defaultRegion` not in `regions` map → pass through to route's original upstream

**Task list:**
- [x] Create `GeoIpResolver` with MaxMind database loader
- [x] Create `GeoRouteGatewayFilterFactory`
- [x] Implement client IP → country → region → URI resolution
- [x] Implement fallback chain
- [x] Inject `X-Geo-Region` header

---

### Step 3: LRU Cache

**Implementation:**
- Caffeine in-process cache: `IP → region code` mappings.
- Default size: 10,000 entries.
- No TTL (GeoIP data changes infrequently; cache clears on gateway restart).

**Task list:**
- [x] Add Caffeine cache for IP → region lookups
- [x] Configure cache size from `cacheSize` param
- [x] Log cache hit rate periodically (TRACE level)

---

### Step 4: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `GEO_ROUTE`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `GEO_ROUTE` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form with region map editor in dashboard

---

### Step 5: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/routing/GeoRouteTest.java`

**Test cases:**
- US IP routes to US upstream
- EU IP routes to EU upstream
- Unknown IP falls back to default region
- Default region not in map → pass through to original upstream
- `X-Geo-Region` header injected correctly
- Cache hit avoids database lookup
- Missing GeoIP database → all requests fall back to default

**Task list:**
- [x] Write tests with mock GeoIP database
- [x] Write fallback chain tests
- [x] Write cache behavior tests

---

## Acceptance Criteria

- [x] Requests routed to correct upstream based on client IP geolocation
- [x] `X-Geo-Region` header injected on every response
- [x] LRU cache avoids per-request disk I/O
- [x] Graceful fallback when GeoIP lookup fails
- [x] MaxMind database loaded once at startup

