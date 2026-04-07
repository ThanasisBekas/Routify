# Initiative GF-07 — SpEL Filter Sandboxing & Security

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 1 (Foundation) · **Owner:** Gateway + Security teams  
> **Category:** Security · **Priority:** High · **Status:** ✅ Complete

---

## Problem Statement

The SpEL expression context in `SpelCustomGatewayFilterFactory` exposes `#request` (the full `ServerHttpRequest` object), which can be used to access `getClass().getClassLoader()` and escape the sandbox. A malicious operator with filter-write permissions could execute arbitrary code on the gateway JVM.

## Solution Overview

Replace `StandardEvaluationContext` with `SimpleEvaluationContext` (read-only data binding), remove the `#request` variable, add expression complexity limits, and emit audit events for every evaluation.

---

## Detailed Implementation Steps

### Step 1: Switch to `SimpleEvaluationContext`

**Files to modify:**
- `routify-api-gateway/.../filter/SpelCustomGatewayFilterFactory.java`

**Changes:**
Replace:
```java
StandardEvaluationContext context = new StandardEvaluationContext();
```
With:
```java
SimpleEvaluationContext context = SimpleEvaluationContext
    .forReadOnlyDataBinding()
    .withInstanceMethods()  // allow String methods like contains(), startsWith()
    .build();
```

`SimpleEvaluationContext` disallows:
- Type references (`T(java.lang.Runtime)`)
- Constructors (`new ProcessBuilder(...)`)
- Method invocation on arbitrary objects (only registered root object and properties)

**Task list:**
- [x] Replace `StandardEvaluationContext` with `SimpleEvaluationContext`
- [x] Verify `forReadOnlyDataBinding()` blocks type references and constructors
- [x] Verify string methods (`contains`, `startsWith`, `endsWith`, `matches`) still work

---

### Step 2: Restrict Context Variables

**Current variables:**
- `#headers` — `Map<String, String>` ✅ keep
- `#params` — `Map<String, String>` ✅ keep
- `#method` — `String` ✅ keep
- `#path` — `String` ✅ keep
- `#request` — `ServerHttpRequest` ❌ **REMOVED**

**New variables:**
- `#contentType` — `String` (from `Content-Type` header)
- `#clientIp` — `String` (resolved from `X-Forwarded-For` or remote address)

**Task list:**
- [x] Remove `#request` variable from context
- [x] Add `#contentType` variable
- [x] Add `#clientIp` variable
- [x] Document all available context variables

---

### Step 3: Add Expression Complexity Limits

**New config parameters:**
```yaml
maxExpressionLength: 500     # characters
maxPropertyDepth: 5          # nested property accessors
```

**Implementation:**
- At config bind time (not per-request), validate expression length.
- Count property accessor depth (`.` separated chains) in the parsed expression AST.
- Reject expressions exceeding limits with clear error response.

**Task list:**
- [x] Add `maxExpressionLength` config parameter (default 500)
- [x] Add `maxPropertyDepth` config parameter (default 5)
- [x] Validate at config bind time
- [x] Reject with clear error message

---

### Step 4: Add `allowedFunctions` Config

**Optional config parameter:**
```yaml
allowedFunctions:
  - contains
  - startsWith
  - endsWith
  - matches
  - toLowerCase
  - toUpperCase
  - trim
  - length
```

When specified, only listed string methods are allowed in expressions. Method calls not in the allowlist cause the expression to short-circuit with a rejection.

**Task list:**
- [x] Add `allowedFunctions` optional config parameter
- [x] Default: all string methods allowed (backward compatible)
- [x] When specified, restrict to listed methods only

---

### Step 5: Audit Event Emission

**Implementation:**
- After every SpEL evaluation, publish a `CUSTOM_SPEL_EVALUATED` audit event to `KafkaTopics.AUDIT_EVENTS`.
- Event payload: `{ routeId, expression (truncated to 200 chars), result (boolean), evaluationTimeMs, clientIp, correlationId }`.
- Use fire-and-forget publishing to avoid adding latency to the filter chain.

**Task list:**
- [x] Define `CUSTOM_SPEL_EVALUATED` event type
- [x] Publish audit event after every evaluation
- [x] Truncate expression in event payload (max 200 chars)
- [x] Fire-and-forget publishing (non-blocking)

---

### Step 6: Migration & Backward Compatibility

**Migration plan:**
1. **Phase 1 (this release):** `#request` has been removed. Expressions using it will fail with an evaluation error and pass through (fail-open). Dashboard documentation updated to point to `#clientIp` and `#contentType` as replacements.
2. **Phase 2 (next minor):** N/A — `#request` is already removed.

**Task list:**
- [x] Add deprecation warning for `#request` usage
- [x] Document migration guide (`#request.remoteAddress` → `#clientIp`, `#request.headers.contentType` → `#contentType`)
- [x] Plan removal in next minor version

---

### Step 7: Security Tests

**Files created:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/SpelSandboxingTest.java`

**Test cases:**
- `T(java.lang.Runtime).getRuntime().exec("ls")` → blocked
- `#request.getClass().getClassLoader()` → blocked (variable removed)
- `new java.io.File("/etc/passwd")` → blocked
- `#headers['Authorization'].contains('Bearer')` → allowed
- `#method == 'GET' and #path.startsWith('/api')` → allowed
- Expression > 500 chars → rejected at config time
- Property depth > 5 → rejected at config time
- Audit event emitted for every evaluation

**Task list:**
- [x] Write security tests for sandbox escape attempts
- [x] Write tests for allowed expressions
- [x] Write tests for complexity limits
- [x] Write audit event emission tests

---

## Acceptance Criteria

- [x] `SimpleEvaluationContext` used — no access to `Class`, `Runtime`, `ProcessBuilder`
- [x] `#request` variable removed from context
- [x] Expression length limit enforced (configurable, default 500 chars)
- [x] Audit event emitted on every evaluation
- [x] Existing `#headers`, `#params`, `#method`, `#path` expressions work unchanged
