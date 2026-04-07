# Initiative GF-18 — Response Header Rewrite Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 5 (Extensions) · **Owner:** Gateway team  
> **Category:** Modification · **Priority:** Medium  
> **Filter type:** `RESPONSE_HEADER_REWRITE`

---

## Problem Statement

The existing `RESPONSE_HEADER_MODIFY` filter can add, set, or remove response headers, but it cannot perform regex-based value rewriting. Common use cases like rewriting `Location` redirect headers from internal URLs to external proxy-facing URLs, or rewriting `Set-Cookie` domain attributes, require regex pattern matching and capture group substitution.

## Solution Overview

A filter that performs regex-based response header value rewriting with pre-compiled patterns, capture group references, and multi-value header support.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/modification/ResponseHeaderRewriteGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `headerName` | `String` | (required) | Response header to rewrite |
| `pattern` | `String` | (required) | Java regex pattern |
| `replacement` | `String` | (required) | Replacement string (`$1`, `$2` refs) |
| `replaceAll` | `boolean` | `false` | Replace all occurrences or just first |

**Implementation:**
1. Pre-compile `java.util.regex.Pattern` at config bind time (not per-request).
2. Wrap the response with a `ServerHttpResponseDecorator` whose `getHeaders()` lazily rewrites matched headers.
3. Use `pattern.matcher(value).replaceFirst()` or `replaceAll()` based on config.
4. If the header has multiple values, rewrite each independently.

**Task list:**
- [x] Create filter factory
- [x] Pre-compile regex at config bind time
- [x] Implement `ServerHttpResponseDecorator` for lazy header rewriting
- [x] Support `replaceFirst` and `replaceAll` modes
- [x] Support multi-value headers

---

### Step 2: Catastrophic Backtracking Protection

**Implementation:**
- Compile the regex pattern with a timeout using `Pattern.compile()`.
- Reject patterns that are known to cause catastrophic backtracking (e.g., nested quantifiers `(a+)+`).
- Add a configurable `matchTimeoutMs` param (default 100ms) — if matching takes longer, abort and pass through original value.

**Task list:**
- [x] Detect and reject pathological regex patterns at config time
- [x] Implement match timeout
- [x] Pass through original value on timeout

---

### Step 3: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `RESPONSE_HEADER_REWRITE`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `RESPONSE_HEADER_REWRITE` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

---

### Step 4: Common Presets Documentation

**Document common configurations:**

**Internal-to-external URL rewrite (Location header):**
```yaml
headerName: Location
pattern: "https://internal\\.service\\.local:8080(.*)"
replacement: "https://api.example.com$1"
replaceAll: false
```

**Set-Cookie domain rewrite:**
```yaml
headerName: Set-Cookie
pattern: "domain=\\.internal\\.local"
replacement: "domain=.example.com"
replaceAll: true
```

**CORS origin normalization:**
```yaml
headerName: Access-Control-Allow-Origin
pattern: "https?://localhost:\\d+"
replacement: "https://app.example.com"
replaceAll: false
```

**Task list:**
- [x] Document common preset configurations
- [x] Add inline help tooltips in dashboard filter form

---

### Step 5: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/modification/ResponseHeaderRewriteTest.java`

**Test cases:**
- `Location` header rewritten from internal to external URL
- Capture group `$1` substituted correctly
- `replaceAll=true` replaces all occurrences in value
- `replaceAll=false` replaces only first occurrence
- Multi-value header: each value rewritten independently
- No match → header value unchanged
- Pathological regex rejected at config time
- Match timeout → original value preserved

**Task list:**
- [x] Write regex rewriting tests
- [x] Write capture group tests
- [x] Write multi-value header tests
- [x] Write security tests (regex DoS prevention)

---

## Acceptance Criteria

- [x] Regex-based response header value rewriting
- [x] Pre-compiled patterns for performance
- [x] Capture group references (`$1`, `$2`) in replacement
- [x] Multi-value headers rewritten independently
- [x] Catastrophic backtracking protection
- [x] Common preset configurations documented

