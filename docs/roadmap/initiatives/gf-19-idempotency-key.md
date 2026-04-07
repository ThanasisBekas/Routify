# Initiative GF-19 — Idempotency Key Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 3 (Resilience & Performance) · **Owner:** Gateway team  
> **Category:** Reliability · **Priority:** High  
> **Filter type:** `IDEMPOTENCY_KEY`  
> **Dependencies:** GF-12 (Response Cache — shared Redis caching patterns)

---

## Problem Statement

Write endpoints (POST, PUT, PATCH) are vulnerable to duplicate execution when clients retry due to network timeouts or load balancer retries. There is no gateway-level deduplication mechanism. Each upstream service must implement its own idempotency logic, leading to inconsistent behavior.

## Solution Overview

A filter that deduplicates write requests using a client-provided idempotency key (per the emerging IETF standard). On first request: execute and cache the response in Redis. On replay: return the cached response without forwarding to upstream.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/reliability/IdempotencyKeyGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `headerName` | `String` | `Idempotency-Key` | Header carrying the idempotency key |
| `ttlSeconds` | `int` | `86400` | How long to remember processed keys (24h) |
| `methods` | `String` | `POST,PUT,PATCH` | HTTP methods to enforce |
| `requireHeader` | `boolean` | `false` | Reject requests without the header |
| `maxCachedBodySize` | `int` | `65536` | Max response body bytes to cache |

**Redis key format:**
```
routify:idempotency:{routeId}:{idempotencyKey}
```

**Task list:**
- [x] Create filter factory with config binding
- [x] Parse `methods` to a set of `HttpMethod`
- [x] Check for idempotency header on matching methods

---

### Step 2: First Request Flow

**Implementation:**
1. Check Redis for `routify:idempotency:{routeId}:{key}`.
2. If not found → set a Redis lock with `NX` (set-if-not-exists) + TTL. Value: `PROCESSING`.
3. Forward request to upstream.
4. On successful response: capture response via `ServerHttpResponseDecorator.writeWith()`.
5. Store cached response in Redis Hash: `{status, headers (JSON), body (Base64)}`.
6. Update lock value from `PROCESSING` to `COMPLETE`.
7. Return response to client with `Idempotency-Key-Status: MISS` header.

**Task list:**
- [x] Implement Redis `NX` lock for first execution
- [x] Implement `ServerHttpResponseDecorator` for response capture
- [x] Store response in Redis Hash (status + headers + body)
- [x] Respect `maxCachedBodySize` — skip caching if response body exceeds limit
- [x] Inject `Idempotency-Key-Status: MISS` response header

---

### Step 3: Replay Flow

**Implementation:**
1. Redis key exists with status `COMPLETE` → read cached response, return directly.
2. Inject `Idempotency-Key-Status: HIT` response header.
3. Set response status code, headers, and body from cached values.
4. Do NOT forward to upstream.

**Concurrent duplicate handling:**
If Redis key exists with status `PROCESSING` → return `409 Conflict` via `GatewayProblemResponse`. This means another request with the same key is still being processed.

**Task list:**
- [x] Implement cache read for replay
- [x] Return cached response on `COMPLETE`
- [x] Return 409 Conflict on `PROCESSING`
- [x] Inject `Idempotency-Key-Status: HIT` header

---

### Step 4: Missing Header Enforcement

When `requireHeader=true` and the idempotency header is absent on a matching method:
- Return `400 Bad Request` with error code `IDEMPOTENCY_KEY_REQUIRED`.
- ProblemDetail body explains the requirement.

When `requireHeader=false` (default): pass through to upstream without idempotency logic.

**Task list:**
- [x] Implement `requireHeader` enforcement
- [x] Return 400 with clear error message

---

### Step 5: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `IDEMPOTENCY_KEY`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `IDEMPOTENCY_KEY` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

---

### Step 6: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/reliability/IdempotencyKeyTest.java`

**Test cases:**
- First POST with key → MISS, response cached
- Replay POST with same key → HIT, cached response returned
- Concurrent duplicate → 409 Conflict
- Different key → independent execution
- TTL expiry → key evicted, next request is MISS
- `requireHeader=true`, no header → 400
- `requireHeader=false`, no header → pass through
- GET request → idempotency logic bypassed
- Response body > `maxCachedBodySize` → response served but not cached
- `Idempotency-Key-Status` header present on all processed responses

**Task list:**
- [x] Write first-request/replay lifecycle tests
- [x] Write concurrent duplicate tests
- [x] Write TTL expiry tests
- [x] Write `requireHeader` enforcement tests

---

## Acceptance Criteria

- [x] First request executes and caches response in Redis
- [x] Replay request returns cached response without upstream call
- [x] Concurrent duplicates rejected with 409 Conflict
- [x] `Idempotency-Key-Status` header on every processed response
- [x] TTL-based key expiry (default 24 hours)
- [x] Configurable header name and enforced methods
- [x] `requireHeader=true` rejects requests without the key
