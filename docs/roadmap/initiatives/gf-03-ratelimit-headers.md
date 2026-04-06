# Initiative GF-03 — Rate Limiter `X-RateLimit-*` Response Headers

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 3 (Resilience & Performance) · **Owner:** Gateway team  
> **Category:** Rate Limiting · **Priority:** Medium  
> **Dependencies:** GF-01 (Shared Key Resolver), GF-02 (Unified Error Response Builder)

---

## Problem Statement

Neither rate limiter emits standard `X-RateLimit-*` headers on successful responses. API consumers cannot proactively slow down before hitting the limit. Only the rejection response includes a basic `X-RateLimit-Window` header.

## Solution Overview

Inject `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers on every response (allow and reject). Modify the Redis Lua scripts to return both `count` and `ttl` to compute reset timestamps without additional Redis calls.

---

## Detailed Implementation Steps

### Step 1: Modify Redis Lua Scripts

**Files to modify:**
- `routify-api-gateway/src/main/resources/scripts/fixed_window_rate_limit.lua`
- `routify-api-gateway/src/main/resources/scripts/sliding_window_rate_limit.lua`

**Changes:**
Both Lua scripts currently return a single value (`count` or `allowed` boolean). Modify to return a 2-element array: `{count, ttl}` where `ttl` is the remaining time in seconds until the current window resets.

```lua
-- Fixed window: return {current_count, remaining_ttl_seconds}
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end
local ttl = redis.call('TTL', KEYS[1])
return {count, ttl}
```

**Task list:**
- [ ] Modify fixed window Lua script to return `{count, ttl}` tuple
- [ ] Modify sliding window Lua script to return `{count, ttl}` tuple
- [ ] Ensure backward compatibility (callers handle tuple response)

---

### Step 2: Update Filter Factories to Inject Headers

**Files to modify:**
- `routify-api-gateway/.../filter/ratelimit/FixedWindowRateLimitGatewayFilterFactory.java`
- `routify-api-gateway/.../filter/ratelimit/SlidingWindowRateLimitGatewayFilterFactory.java`

**Headers to inject on every response:**
```
X-RateLimit-Limit: <maxRequests>
X-RateLimit-Remaining: <max(0, maxRequests - count)>
X-RateLimit-Reset: <epoch-seconds-of-window-reset>
```

**Additional header on 429 rejection:**
```
Retry-After: <seconds-until-window-reset>
```

**Implementation pattern:**
```java
long remaining = Math.max(0, config.getMaxRequests() - count);
long resetEpoch = Instant.now().plusSeconds(ttl).getEpochSecond();

exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(config.getMaxRequests()));
exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(remaining));
exchange.getResponse().getHeaders().set("X-RateLimit-Reset", String.valueOf(resetEpoch));
```

**Task list:**
- [ ] Parse `{count, ttl}` tuple from Lua script response
- [ ] Inject `X-RateLimit-Limit` on every response
- [ ] Inject `X-RateLimit-Remaining` on every response
- [ ] Inject `X-RateLimit-Reset` on every response
- [ ] Inject `Retry-After` on 429 responses (via `GatewayProblemResponse` from GF-02)

---

### Step 3: Add `includeHeaders` Config Flag

**Files to modify:**
- Rate limit filter config classes

**New config parameter:**
```java
private boolean includeHeaders = true;
```

When `includeHeaders=false`, all `X-RateLimit-*` headers are suppressed. The `Retry-After` header on 429 responses is always included regardless of this flag (it's semantically required by RFC 6585).

**Task list:**
- [ ] Add `includeHeaders` boolean config parameter (default `true`)
- [ ] Conditionally inject headers based on config
- [ ] Always include `Retry-After` on 429

---

### Step 4: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/ratelimit/RateLimitHeadersTest.java`

**Test cases:**
- `X-RateLimit-Limit` equals configured `maxRequests`
- `X-RateLimit-Remaining` decrements correctly per request
- `X-RateLimit-Reset` is a valid epoch timestamp in the future
- `Retry-After` present on 429 and absent on 200
- `includeHeaders=false` suppresses all rate limit headers (except `Retry-After` on 429)
- Integration test validates header values against Redis state

**Task list:**
- [ ] Write unit tests for header injection logic
- [ ] Write integration tests validating header values vs. Redis state
- [ ] Write tests for `includeHeaders=false` suppression

---

## Acceptance Criteria

- [ ] `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` on every 2xx/4xx response
- [ ] `Retry-After` on 429 responses
- [ ] Lua scripts return `{count, ttl}` tuple
- [ ] `includeHeaders=false` suppresses all rate limit headers
- [ ] Integration test validates header values against Redis state

