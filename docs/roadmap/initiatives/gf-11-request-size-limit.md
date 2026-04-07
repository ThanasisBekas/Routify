# Initiative GF-11 — Request Size Limit Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 2 (High-Priority Filters) · **Owner:** Gateway team  
> **Category:** Validation · **Priority:** High  
> **Filter type:** `REQUEST_SIZE_LIMIT`  
> **Dependencies:** GF-02 (Unified Error Response Builder)
> **Status:** ✅ Completed

---

## Problem Statement

The deprecated `VALIDATE_SIZE` filter type delegated to SCG's built-in `RequestSize` filter with minimal configuration. It has been deprecated in favor of a custom implementation that supports configurable error responses, tenant-aware limits, and Micrometer metrics. Currently, there is no active filter that enforces per-route request body size limits.

## Solution Overview

A custom filter that enforces maximum request body size with two enforcement stages: header-based fast rejection and streaming byte counting for chunked transfers. Supports tenant-aware limits from `TenantPlan` quotas.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/validation/RequestSizeLimitGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxSize` | `String` | `5MB` | Max request body size (supports `KB`, `MB`, `GB` suffixes) |
| `checkContentLength` | `boolean` | `true` | Reject based on `Content-Length` header |
| `checkActualSize` | `boolean` | `true` | Enforce by counting actual body bytes |
| `tenantAware` | `boolean` | `false` | Resolve per-tenant limits from `TenantPlan` |

**Implementation:**

**Stage 1 (header-based fast path):**
- Parse `Content-Length` header.
- If present and exceeds `maxSize` → reject immediately with HTTP 413.
- No body reading needed; fast rejection before any buffering.

**Stage 2 (streaming enforcement):**
- Wrap the request body with a `DataBuffer` decorator that maintains a running byte counter.
- On each chunk: `counter += buffer.readableByteCount()`.
- If `counter > maxSize` → signal error, which triggers a 413 response.

**Task list:**
- [x] Create filter factory with size parsing (`5MB` → bytes)
- [x] Implement Stage 1: `Content-Length` header check
- [x] Implement Stage 2: streaming byte counter
- [x] Use `GatewayProblemResponse` for 413 response (includes `maxSize` in body)

---

### Step 2: Tenant-Aware Mode

**Implementation:**
When `tenantAware=true`:
1. Read `X-Tenant-Id` header from the exchange.
2. Look up the tenant's plan (from exchange attribute set by `TenantContextGatewayFilterFactory`).
3. Resolve plan-specific size limit (new field on `TenantPlan` or a configurable map).
4. Use the tenant-specific limit instead of the static `maxSize`.

**Task list:**
- [x] Read tenant plan from exchange attributes
- [x] Resolve tenant-specific size limit
- [x] Fall back to static `maxSize` if tenant not resolved

---

### Step 3: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `REQUEST_SIZE_LIMIT`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `REQUEST_SIZE_LIMIT` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

---

### Step 4: Metrics

**Metrics:**
- `routify.filter.request_size.rejected` — counter, tagged by `routeId`
- `routify.filter.request_size.bytes` — distribution summary of request body sizes

**Task list:**
- [x] Register Micrometer counter and distribution summary
- [x] Record body size on every request (from `Content-Length` or streamed count)
- [x] Increment rejected counter on 413

---

### Step 5: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/validation/RequestSizeLimitTest.java`

**Test cases:**
- `Content-Length: 10MB` with `maxSize=5MB` → 413 (fast path)
- Chunked request exceeding `maxSize` → 413 (streaming path)
- Request within limit → passes through
- `maxSize` parsed correctly: `5MB`, `512KB`, `1GB`
- Missing `Content-Length` with `checkContentLength=true` → falls through to streaming check
- `tenantAware=true` uses tenant-specific limit
- Response body contains `maxSize` in ProblemDetail

**Task list:**
- [x] Write tests for header-based rejection
- [x] Write tests for streaming-based rejection
- [x] Write tests for size parsing
- [x] Write tests for tenant-aware mode

---

## Acceptance Criteria

- [x] Requests exceeding `maxSize` rejected with HTTP 413
- [x] Header-based fast rejection for known `Content-Length`
- [x] Streaming enforcement for chunked transfers
- [x] Tenant-aware mode resolves per-tenant limits
- [x] RFC 9457 ProblemDetail response with `maxSize` in body
- [x] Micrometer metrics for rejected count and body size distribution
