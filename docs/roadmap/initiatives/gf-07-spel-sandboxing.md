# Initiative GF-07 — SpEL Filter Sandboxing & Security

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 1 (Foundation) · **Owner:** Gateway + Security teams  
> **Category:** Security · **Priority:** High

---

## Problem Statement

The SpEL expression context in `SpelCustomGatewayFilterFactory` exposes `#request` (the full `ServerHttpRequest` object), which can be used to access `getClass().getClassLoader()` and escape the sandbox. A malicious operator with filter-write permissions could execute arbitrary code on the gateway JVM.

## Solution Overview

Replace `StandardEvaluationContext` with `SimpleEvaluationContext` (read-only data binding), remove the `#request` variable, add expression complexity limits, and emit audit events for every evaluation.

---

## Detailed Implementation Steps

### Step 1: Switch to `SimpleEvaluationContext`

**Files to modify:**
- `routify-api-gateway/.../filter/custom/SpelCustomGatewayFilterFactory.java`

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
- [ ] Replace `StandardEvaluationContext` with `SimpleEvaluationContext`
- [ ] Verify `forReadOnlyDataBinding()` blocks type references and constructors
- [ ] Verify string methods (`contains`, `startsWith`, `endsWith`, `matches`) still work

---

### Step 2: Restrict Context Variables

**Current variables:**
- `#headers` — `Map<String, String>` ✅ keep
- `#params` — `Map<String, String>` ✅ keep
- `#method` — `String` ✅ keep
- `#path` — `String` ✅ keep
- `#request` — `ServerHttpRequest` ❌ **REMOVE**

**New variables:**
- `#contentType` — `String` (from `Content-Type` header)
- `#clientIp` — `String` (resolved from `X-Forwarded-For` or remote address)

**Task list:**
- [ ] Remove `#request` variable from context
- [ ] Add `#contentType` variable
- [ ] Add `#clientIp` variable
- [ ] Document all available context variables

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
- Reject expressions exceeding limits with `RoutifyException.Validation`.

**Task list:**
- [ ] Add `maxExpressionLength` config parameter (default 500)
- [ ] Add `maxPropertyDepth` config parameter (default 5)
- [ ] Validate at config bind time
- [ ] Reject with clear error message

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
- [ ] Add `allowedFunctions` optional config parameter
- [ ] Default: all string methods allowed (backward compatible)
- [ ] When specified, restrict to listed methods only

---

### Step 5: Audit Event Emission

**Implementation:**
- After every SpEL evaluation, publish a `CUSTOM_SPEL_EVALUATED` telemetry event to `KafkaTopics.AUDIT_EVENTS` (or `REQUEST_TELEMETRY`).
- Event payload: `{ routeId, expression (truncated to 200 chars), result (boolean), evaluationTimeMs, clientIp, correlationId }`.
- Use fire-and-forget publishing to avoid adding latency to the filter chain.

**Task list:**
- [ ] Define `CUSTOM_SPEL_EVALUATED` event type
- [ ] Publish audit event after every evaluation
- [ ] Truncate expression in event payload (max 200 chars)
- [ ] Fire-and-forget publishing (non-blocking)

---

### Step 6: Migration & Backward Compatibility

**Migration plan:**
1. **Phase 1 (this release):** Log a `WARN` if any existing SpEL expression references `#request`. The expression still works but the warning includes a migration guide.
2. **Phase 2 (next minor):** Remove `#request` entirely. Expressions using it will fail with a clear error message pointing to `#clientIp` and `#contentType` as replacements.

**Task list:**
- [ ] Add deprecation warning for `#request` usage
- [ ] Document migration guide (`#request.remoteAddress` → `#clientIp`, `#request.headers.contentType` → `#contentType`)
- [ ] Plan removal in next minor version

---

### Step 7: Security Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/custom/SpelSandboxingTest.java`

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
- [ ] Write security tests for sandbox escape attempts
- [ ] Write tests for allowed expressions
- [ ] Write tests for complexity limits
- [ ] Write audit event emission tests

---

## Acceptance Criteria

- [ ] `SimpleEvaluationContext` used — no access to `Class`, `Runtime`, `ProcessBuilder`
- [ ] `#request` variable removed from context
- [ ] Expression length limit enforced (configurable, default 500 chars)
- [ ] Audit event emitted on every evaluation
- [ ] Existing `#headers`, `#params`, `#method`, `#path` expressions work unchanged

