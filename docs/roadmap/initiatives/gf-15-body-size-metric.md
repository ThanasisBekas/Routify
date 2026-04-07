# Initiative GF-15 — Request/Response Body Size Logging Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 5 (Extensions) · **Owner:** Gateway team  
> **Category:** Observability · **Priority:** Low  
> **Filter type:** `BODY_SIZE_METRIC`  
> **Status:** ✅ **COMPLETED**

---

## Problem Statement

There is no lightweight way to monitor request and response payload sizes per route. The `RequestLoggerGatewayFilterFactory` captures body content (expensive), but operators often only need size distribution metrics without the overhead of reading or buffering body content.

## Solution Overview

A zero-copy filter that records request and response body sizes as Micrometer distribution summaries by reading `Content-Length` headers. For chunked transfers, wraps the body `Flux<DataBuffer>` with a non-buffering counter that sums `readableByteCount()` per chunk.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/observability/BodySizeMetricGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `includeRequest` | `boolean` | `true` | Record request body size |
| `includeResponse` | `boolean` | `true` | Record response body size |
| `tags` | `Map<String, String>` | `{}` | Additional Micrometer tags |

**Implementation:**

**Request body size:**
1. Read `Content-Length` header → record directly as distribution summary value.
2. If `Content-Length` absent (chunked): wrap the request body `Flux<DataBuffer>` with a `doOnNext()` that sums `readableByteCount()`. Record the total in the post-filter phase.

**Response body size:**
1. Read response `Content-Length` → record directly.
2. If absent: wrap response body with `ServerHttpResponseDecorator.writeWith()` that counts bytes without buffering.

**Task list:**
- [x] Create filter factory
- [x] Implement request body size recording
- [x] Implement response body size recording
- [x] Support `Content-Length` fast path
- [x] Support chunked transfer byte counting
- [x] Zero-copy: never buffer body content

---

### Step 2: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `BODY_SIZE_METRIC`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `BODY_SIZE_METRIC` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

---

### Step 3: Metrics

**Metrics:**
- `routify.request.body.size` — distribution summary, tagged by `routeId`, `method`
- `routify.response.body.size` — distribution summary, tagged by `routeId`, `method`, `status`

**Task list:**
- [x] Register Micrometer distribution summaries
- [x] Tag with `routeId`, `method`, `status`
- [x] Include custom tags from config

---

### Step 4: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/observability/BodySizeMetricTest.java`

**Test cases:**
- `Content-Length: 1024` → metric recorded as 1024
- Chunked request → metric recorded as sum of chunk sizes
- `includeRequest=false` → request size not recorded
- `includeResponse=false` → response size not recorded
- Custom tags appear in metric

**Task list:**
- [x] Write tests for `Content-Length` fast path
- [x] Write tests for chunked transfer counting
- [x] Write tests for config flags

---

## Acceptance Criteria

- [x] Request and response body sizes recorded as Micrometer distribution summaries
- [x] `Content-Length` header used as fast path (no body reading)
- [x] Chunked transfers counted via non-buffering byte counter
- [x] Zero-copy: body content never buffered or read
- [x] Custom tags configurable per filter instance
