# Initiative GF-21 — Mock Response Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 5 (Extensions) · **Owner:** Gateway team  
> **Category:** Developer Experience · **Priority:** Medium  
> **Filter type:** `MOCK_RESPONSE`  
> **Status:** ✅ **COMPLETED**

---

## Problem Statement

During API development, operators often need to stub endpoints that don't have a live upstream yet (contract-first development). During maintenance windows, they need to return informative responses without forwarding to upstream. Currently, there is no way to do this at the gateway level — operators must deploy a mock service or take the upstream offline and let the gateway return a generic error.

## Solution Overview

A filter that returns a configurable static response without forwarding the request to any upstream service. Supports template interpolation with request attributes, simulated latency for timeout testing, and conditional activation via header.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/devex/MockResponseGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `status` | `int` | `200` | HTTP status code |
| `contentType` | `String` | `application/json` | Response `Content-Type` |
| `body` | `String` | `{}` | Response body (supports template interpolation) |
| `headers` | `Map<String, String>` | `{}` | Additional response headers |
| `delay` | `long` | `0` | Simulated latency in milliseconds |
| `conditionHeader` | `String` | (optional) | Only mock when this header is present |

**Implementation:**
1. Short-circuit the filter chain — do NOT call `chain.filter(exchange)`.
2. Set response status code from config.
3. Set `Content-Type` and additional headers.
4. Write body (with template interpolation) to response.
5. If `delay > 0`, use `Mono.delay(Duration.ofMillis(delay))` before writing.

**Task list:**
- [x] Create filter factory
- [x] Short-circuit filter chain (no upstream call)
- [x] Set status, content type, and custom headers
- [x] Write response body
- [x] Implement delay simulation

---

### Step 2: Template Interpolation

**Supported placeholders in `body`:**
- `${method}` — HTTP method
- `${path}` — request path
- `${header:X-Foo}` — value of request header `X-Foo`
- `${param:id}` — value of query parameter `id`
- `${timestamp}` — current ISO-8601 timestamp
- `${correlationId}` — correlation ID from `RoutifyHeaders`

**Example:**
```json
{
  "message": "Mock response for ${method} ${path}",
  "requestedBy": "${header:X-User-Id}",
  "timestamp": "${timestamp}"
}
```

**Implementation:**
- Parse `body` template at config bind time (extract placeholder positions).
- Resolve placeholders per-request.
- Unknown placeholders resolve to empty string.

**Task list:**
- [x] Parse template placeholders at config time
- [x] Resolve `${method}`, `${path}`, `${timestamp}`, `${correlationId}`
- [x] Resolve `${header:name}` and `${param:name}`
- [x] Unknown placeholders resolve to empty string

---

### Step 3: Conditional Activation

When `conditionHeader` is configured:
- If the request contains the specified header → return mock response.
- If the header is absent → pass through to upstream (call `chain.filter(exchange)`).

This enables per-request mock toggling without changing the filter config.

**Task list:**
- [x] Implement conditional activation via header presence
- [x] Pass through when condition not met

---

### Step 4: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `MOCK_RESPONSE`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `MOCK_RESPONSE` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form with body editor (code textarea) in dashboard

---

### Step 5: Maintenance Mode Pattern

**Documentation:**
Document a "maintenance mode" pattern using the mock response filter:
```yaml
filterType: MOCK_RESPONSE
config:
  status: 503
  contentType: application/json
  body: '{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Service is under scheduled maintenance. Please try again later."}'
  headers:
    Retry-After: "3600"
```

Apply to all routes via global filter entry to put the entire gateway in maintenance mode.

**Task list:**
- [x] Document maintenance mode pattern
- [x] Document API stubbing pattern
- [x] Add dashboard presets/templates for common mock scenarios

---

### Step 6: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/devex/MockResponseTest.java`

**Test cases:**
- Mock returns configured status, body, and content type
- Custom headers set on response
- Template interpolation resolves `${method}`, `${path}`
- `${header:X-Foo}` resolves to header value
- `${param:id}` resolves to query parameter
- Unknown placeholder → empty string
- `delay=500` adds ~500ms latency
- `conditionHeader=X-Mock` present → mock response
- `conditionHeader=X-Mock` absent → passes through to upstream
- No upstream call made when mock is active

**Task list:**
- [x] Write mock response tests
- [x] Write template interpolation tests
- [x] Write delay simulation tests
- [x] Write conditional activation tests

---

## Acceptance Criteria

- [x] Static response returned without forwarding to upstream
- [x] Template interpolation with request attributes
- [x] Simulated latency for timeout testing
- [x] Conditional activation via header presence
- [x] Maintenance mode pattern documented
- [x] No upstream call when mock is active
