# Initiative GF-14 — Retry v2 Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 3 (Resilience & Performance) · **Owner:** Gateway team  
> **Category:** Resilience · **Priority:** High  
> **Filter type:** `RETRY_V2`  
> **Dependencies:** GF-19 (Idempotency Key — for idempotency header awareness)  
> **Status:** ✅ **COMPLETED**

---

## Problem Statement

The deprecated `RETRY` filter type delegated to SCG's built-in retry mechanism with minimal configuration. It lacked exponential backoff with jitter, idempotency awareness, and configurable retry conditions beyond HTTP status codes. Retry storms across concurrent clients could overwhelm upstream services.

## Solution Overview

A custom retry filter using Reactor's `retryWhen(Retry.backoff(...))` with exponential backoff, jitter, idempotency-aware retry logic, and per-route configuration.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/resilience/RetryV2GatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxRetries` | `int` | `3` | Maximum retry attempts |
| `initialBackoffMs` | `long` | `500` | Initial backoff delay |
| `maxBackoffMs` | `long` | `5000` | Maximum backoff delay |
| `backoffMultiplier` | `double` | `2.0` | Backoff multiplier |
| `jitterFactor` | `double` | `0.25` | Random jitter factor (0.0–1.0) |
| `retryableStatuses` | `String` | `502,503,504` | HTTP status codes to retry |
| `retryableMethods` | `String` | `GET,HEAD,OPTIONS` | HTTP methods safe to retry |
| `retryOnTimeout` | `boolean` | `true` | Retry on connection/read timeouts |
| `idempotencyHeader` | `String` | `Idempotency-Key` | Header that signals idempotent request |

**Implementation:**
```java
// Reactor retry with exponential backoff + jitter
Retry retrySpec = Retry.backoff(config.maxRetries, Duration.ofMillis(config.initialBackoffMs))
    .maxBackoff(Duration.ofMillis(config.maxBackoffMs))
    .multiplier(config.backoffMultiplier)
    .jitter(config.jitterFactor)
    .filter(throwable -> isRetryable(throwable, exchange, config))
    .doBeforeRetry(signal -> {
        exchange.getRequest().mutate()
            .header("X-Retry-Count", String.valueOf(signal.totalRetries() + 1));
    });

return chain.filter(exchange).retryWhen(retrySpec);
```

**Task list:**
- [x] Create filter factory with Reactor `retryWhen`
- [x] Implement exponential backoff with configurable multiplier
- [x] Implement jitter using `ThreadLocalRandom`
- [x] Parse `retryableStatuses` and `retryableMethods`

---

### Step 2: Idempotency Awareness

**Implementation:**
- Safe methods (`GET`, `HEAD`, `OPTIONS`) → always retryable (subject to status code matching).
- Unsafe methods (`POST`, `PUT`, `PATCH`, `DELETE`) → only retryable if the `Idempotency-Key` header is present.
- When the idempotency header is present, the client guarantees the operation is safe to retry.
- This integrates with GF-19 (Idempotency Key Filter) — if both filters are active on a route, the retry filter knows the upstream has deduplication.

**Task list:**
- [x] Check HTTP method safety before retrying
- [x] Allow retry of unsafe methods when idempotency header is present
- [x] Log a WARN if retrying an unsafe method without idempotency header

---

### Step 3: Timeout Retry

**Implementation:**
When `retryOnTimeout=true`, catch `TimeoutException`, `ConnectTimeoutException`, and `ReadTimeoutException` in the retry filter predicate.

**Task list:**
- [x] Catch timeout exceptions in retry predicate
- [x] Support `retryOnTimeout=false` to skip timeout retries

---

### Step 4: Retry Header Injection

**Implementation:**
Inject `X-Retry-Count: <n>` header on retried requests. The upstream service can use this for observability (e.g., logging, metrics).

**Task list:**
- [x] Inject `X-Retry-Count` header on each retry attempt
- [x] Count starts at 1 on first retry

---

### Step 5: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `RETRY_V2`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `RETRY_V2` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

---

### Step 6: Metrics

**Metrics:**
- `routify.filter.retry.attempt` — counter, tagged by `routeId`, `retryNumber`
- `routify.filter.retry.exhausted` — counter (all retries failed)
- `routify.filter.retry.success` — counter (succeeded after retry)

**Task list:**
- [x] Register Micrometer counters
- [x] Increment on each retry attempt
- [x] Track exhausted vs. success outcomes

---

### Step 7: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/resilience/RetryV2Test.java`

**Test cases:**
- Upstream returns 503 → retried → succeeds on 2nd attempt
- All retries exhausted → 503 returned to client
- Exponential backoff timings verified (approximately correct with jitter)
- `POST` without `Idempotency-Key` → not retried on 503
- `POST` with `Idempotency-Key` → retried on 503
- `retryOnTimeout=true` retries on `TimeoutException`
- `X-Retry-Count` header present on retried requests
- Jitter ensures non-deterministic backoff timing

**Task list:**
- [x] Write retry lifecycle tests
- [x] Write idempotency awareness tests
- [x] Write timeout retry tests
- [x] Write backoff timing tests (statistical verification)

---

## Acceptance Criteria

- [x] Exponential backoff with configurable multiplier and max
- [x] Jitter prevents retry storms across concurrent clients
- [x] Idempotency-aware: unsafe methods only retried with idempotency header
- [x] `X-Retry-Count` header on retried requests
- [x] Timeout retries configurable
- [x] Micrometer metrics for attempt/exhausted/success
