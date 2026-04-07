# Initiative GF-05 — Jolt Transform Response-Phase Support

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 4 (Advanced Features) · **Owner:** Gateway team  
> **Category:** Body Transformation · **Priority:** Medium · **Status:** ✅ Complete

---

## Problem Statement

The `phase` config param on `JoltTransformGatewayFilterFactory` accepts `RESPONSE` but the implementation only handles `REQUEST`. Response transformation silently falls through to request mode, which is a confusing no-op for operators who expect response body transformation.

## Solution Overview

Implement full response-phase Jolt transformation using a `ServerHttpResponseDecorator`, update `Content-Length`, and add support for a `BOTH` phase that transforms request and response with independent Jolt specs.

---

## Detailed Implementation Steps

### Step 1: Implement Response-Phase Transformation

**Files to modify:**
- `routify-api-gateway/.../filter/JoltTransformGatewayFilterFactory.java`

**Implementation:**
1. When `phase=RESPONSE`, wrap the response with a `ServerHttpResponseDecorator` that intercepts `writeWith()`.
2. Join the response body `Flux<DataBuffer>` into a single byte array.
3. Parse as JSON, apply the Jolt `Chainr`, serialize the transformed result.
4. Update `Content-Length` header to reflect the new body size.
5. Only transform `application/json` responses; pass other content types through unchanged.

**Task list:**
- [x] Implement `ServerHttpResponseDecorator` for response body transformation
- [x] Join response body flux into single buffer
- [x] Apply Jolt `Chainr` to response JSON
- [x] Update `Content-Length` after transformation
- [x] Pass non-JSON responses through unchanged

---

### Step 2: Add `maxBodySize` Config Param

**New config parameter:**
```yaml
maxBodySize: 1048576  # 1 MB default
```

When the response body exceeds `maxBodySize`, skip transformation and pass through the original body unchanged. Log a warning with the route ID and actual body size.

**Task list:**
- [x] Add `maxBodySize` config parameter (default 1 MB)
- [x] Skip transformation for oversized bodies
- [x] Log warning with route ID and body size

---

### Step 3: Support `BOTH` Phase

**New config field:**
```yaml
responseSpec: "[{\"operation\":\"shift\",\"spec\":{...}}]"
```

When `phase=BOTH`:
1. Apply the primary `spec` to the request body (existing behavior).
2. Apply the `responseSpec` to the upstream response body (new).
3. Both specs are compiled to `Chainr` instances at config bind time.

**Task list:**
- [x] Add `responseSpec` config field
- [x] Compile `responseSpec` to `Chainr` at config bind time
- [x] Apply request spec on REQUEST phase, response spec on RESPONSE phase
- [x] Validate that `responseSpec` is provided when `phase=BOTH`

---

### Step 4: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/JoltTransformResponseTest.java`

**Test cases:**
- `phase=RESPONSE` transforms upstream JSON response correctly
- `phase=BOTH` transforms both request and response
- `Content-Length` updated after transformation
- Non-JSON response (`text/html`) passes through unchanged
- Body exceeding `maxBodySize` passes through with warning
- Empty response body handled gracefully
- Malformed JSON response produces a 502 error (not a silent failure)

**Task list:**
- [x] Write tests for response transformation
- [x] Write tests for `BOTH` phase
- [x] Write tests for edge cases (non-JSON, oversized, empty, malformed)

---

## Acceptance Criteria

- [x] `phase=RESPONSE` transforms upstream response body before returning to client
- [x] `phase=BOTH` transforms request and response with independent specs
- [x] `Content-Length` updated on transformed responses
- [x] Non-JSON responses pass through unchanged
- [x] Bodies exceeding `maxBodySize` pass through with a warning log
