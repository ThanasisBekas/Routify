# Initiative GF-01 — Extract Shared Key Resolver

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 1 (Foundation) · **Owner:** Gateway team  
> **Category:** Rate Limiting · **Priority:** High

---

## Problem Statement

The `resolveKey()` method is duplicated verbatim in both `FixedWindowRateLimitGatewayFilterFactory` and `SlidingWindowRateLimitGatewayFilterFactory` (~25 lines each). The `RouteDefinitionBuilder` has a third, separate key resolver mapping for the SCG built-in token bucket. Any new key resolver strategy (e.g., `ROUTE`, `GEO`, composite keys) must be added in three places, violating DRY and increasing the risk of inconsistencies.

## Solution Overview

Extract a shared `RateLimitKeyResolver` component used by all rate limiter paths, and extend it with new resolution strategies (`ROUTE`, `HEADER:<name>`, `COMPOSITE:<a>:<b>`).

---

## Detailed Implementation Steps

### Step 1: Extract `RateLimitKeyResolver` Component

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/ratelimit/RateLimitKeyResolver.java`

**Implementation:**
```java
@Component
public class RateLimitKeyResolver {

    /**
     * Resolve a rate-limit key from the exchange based on the configured strategy.
     *
     * Supported strategies:
     *   IP          — client IP address (X-Forwarded-For aware)
     *   USER        — authenticated user ID from SecurityContext
     *   TENANT      — tenant ID from X-Tenant-Id header
     *   API_KEY     — API key from X-Api-Key header
     *   TENANT_USER — composite tenantId:userId
     *   ROUTE       — route ID (new)
     *   HEADER:<n>  — value of header <n> (new)
     *   COMPOSITE:<a>:<b> — concatenation of two strategies (new)
     */
    public String resolve(ServerWebExchange exchange, String strategy) {
        // Strategy dispatch with pattern matching
    }
}
```

**Task list:**
- [x] Create `RateLimitKeyResolver` as a Spring `@Component`
- [x] Migrate existing `IP`, `USER`, `TENANT`, `API_KEY`, `TENANT_USER` strategies from inline code
- [x] Add `ROUTE` strategy (resolves to route ID from exchange attribute)
- [x] Add `HEADER:<name>` strategy (resolves to arbitrary header value)
- [x] Add `COMPOSITE:<a>:<b>` strategy (concatenation of two strategies separated by `:`)
- [x] Handle missing/null values gracefully — fall back to client IP

---

### Step 2: Inject Into Rate Limit Filter Factories

**Files to modify:**
- `routify-api-gateway/.../filter/ratelimit/FixedWindowRateLimitGatewayFilterFactory.java`
- `routify-api-gateway/.../filter/ratelimit/SlidingWindowRateLimitGatewayFilterFactory.java`

**Changes:**
1. Add `RateLimitKeyResolver` as a constructor parameter.
2. Replace inline `resolveKey()` method with `keyResolver.resolve(exchange, config.getKeyStrategy())`.
3. Mark the old private `resolveKey()` method as `@Deprecated(forRemoval = true)`.
4. Remove deprecated method in the next minor version.

**Task list:**
- [x] Inject `RateLimitKeyResolver` into `FixedWindowRateLimitGatewayFilterFactory`
- [x] Inject `RateLimitKeyResolver` into `SlidingWindowRateLimitGatewayFilterFactory`
- [x] Replace all inline `resolveKey()` calls
- [x] Deprecate inline methods

---

### Step 3: SCG Token Bucket Integration

**Files to modify:**
- `routify-api-gateway/.../config/RouteDefinitionBuilder.java`

**Changes:**
1. Register a `KeyResolver` bean (`@Bean routeKeyResolver`) that delegates to `RateLimitKeyResolver` for the SCG built-in `RequestRateLimiter` filter.
2. Remove the inline key resolver mapping in `RouteDefinitionBuilder`.

**Task list:**
- [x] Create `KeyResolver` bean wrapping `RateLimitKeyResolver`
- [x] Replace inline key resolver mapping in `RouteDefinitionBuilder`

---

### Step 4: Unit Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/ratelimit/RateLimitKeyResolverTest.java`

**Test cases:**
- `IP` strategy resolves from `X-Forwarded-For` when present, falls back to `remoteAddress`
- `USER` strategy resolves from exchange attribute / security context
- `TENANT` strategy resolves from `X-Tenant-Id` header
- `API_KEY` strategy resolves from `X-Api-Key` header
- `TENANT_USER` strategy concatenates tenant and user
- `ROUTE` strategy resolves from exchange route attribute
- `HEADER:X-Custom` strategy resolves arbitrary header
- `COMPOSITE:TENANT:USER` strategy concatenates two strategies
- Unknown strategy falls back to client IP
- Null/missing values handled gracefully

**Task list:**
- [x] Write unit tests for all strategy variants
- [x] Write edge case tests (null headers, missing attributes)
- [x] Verify backward compatibility with existing filter configurations

---

## Acceptance Criteria

- [x] Single `RateLimitKeyResolver` class used by all 3 rate limiter paths
- [x] `ROUTE`, `HEADER:<name>`, `COMPOSITE` strategies supported
- [x] Existing `IP`, `USER`, `TENANT`, `API_KEY`, `TENANT_USER` strategies unchanged
- [x] Unit tests for every strategy variant
- [x] No behavioral regression in existing rate limit filters

