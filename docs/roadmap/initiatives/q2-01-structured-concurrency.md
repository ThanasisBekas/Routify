# Initiative Q2-01 — Structured Concurrency & Scoped Values

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 1–4 · **Owner:** routify-common + all services

---

## Problem Statement

`SecurityContext` uses `ThreadLocal` — a carry-over from the platform-thread era. With virtual threads enabled, `ThreadLocal` creates two risks: (1) pinned carriers if a virtual thread blocks while holding a `ThreadLocal`, and (2) silent `null` in child virtual threads forked via `Executors.newVirtualThreadPerTaskExecutor()` because `ThreadLocal` does not inherit into new virtual threads by default (`InheritableThreadLocal` does, but it's mutable and leaks across tasks).

Meanwhile, `DashboardStatsService` calls 4 downstream services sequentially over RabbitMQ (~40ms each = ~160ms total). These calls are independent and should execute in parallel with structured lifetime management.

## Solution Overview

1. Replace `ThreadLocal<SecurityContext>` with `ScopedValue<SecurityContext>` — immutable, inheritable by child virtual threads via `StructuredTaskScope`, and automatically cleared on scope exit.
2. Introduce `StructuredTaskScope` for fan-out RPC patterns in admin-api and ai-service.

---

## Detailed Implementation Steps

### Step 1: `SecurityContext` Migration to `ScopedValue`

**File to modify:** `routify-common/src/main/java/io/routify/common/security/SecurityContext.java`

**Before (current):**
```java
private static final ThreadLocal<SecurityContext> HOLDER = new ThreadLocal<>();
public static void set(SecurityContext ctx) { HOLDER.set(ctx); }
public static SecurityContext current() { return HOLDER.get(); }
public static void clear() { HOLDER.remove(); clearMdc(); }
```

**After (Java 25):**
```java
public static final ScopedValue<SecurityContext> SCOPE = ScopedValue.newInstance();

public static SecurityContext current() {
    return SCOPE.orElseThrow(() ->
        new IllegalStateException("No SecurityContext bound to current scope"));
}

// Convenience: run a block with a bound SecurityContext
public static <T> T runWith(SecurityContext ctx, Callable<T> task) throws Exception {
    return ScopedValue.callWhere(SCOPE, ctx, task);
}

public static void runWith(SecurityContext ctx, Runnable task) {
    ScopedValue.runWhere(SCOPE, ctx, task);
}
```

**Task list:**
- [ ] Replace `ThreadLocal` with `ScopedValue` in `SecurityContext`
- [ ] Add `runWith()` convenience methods
- [ ] Keep `setMdc()` / `clearMdc()` for SLF4J MDC (it's still thread-bound, needed for logging)
- [ ] Add `@Deprecated` to old `set()` / `clear()` methods during transition; remove after all callers migrate

---

### Step 2: JWT Filter Migration (all services)

Every service has a JWT filter that currently calls `SecurityContext.set(ctx)` and `SecurityContext.clear()`. Replace with `ScopedValue.runWhere()`.

**Pattern — Spring MVC (imperative services):**
```java
// In JwtAuthFilter.doFilterInternal():
SecurityContext ctx = new SecurityContext(userId, tenantId, username, role, correlationId);
ScopedValue.runWhere(SecurityContext.SCOPE, ctx, () -> {
    ctx.setMdc();
    try {
        filterChain.doFilter(request, response);
    } finally {
        SecurityContext.clearMdc();
    }
});
```

**Files to modify (one per service):**
- `routify-admin-api/.../config/` — JWT filter
- `routify-route-service/.../config/GatewayPreAuthFilter.java`
- `routify-identity-service/.../security/` — JWT filter
- `routify-audit-service/.../config/` — JWT filter (if present)
- `routify-cert-vault/.../config/` — JWT filter

**Gateway (reactive) stays on `ThreadLocal`** — WebFlux's reactor context is a separate mechanism; `ScopedValue` is for platform/virtual threads only. The reactive gateway's `SecurityContext` propagation via `Mono.contextWrite()` remains unchanged.

**Task list:**
- [ ] Migrate admin-api JWT filter
- [ ] Migrate route-service JWT filter  
- [ ] Migrate identity-service JWT filter
- [ ] Migrate audit-service JWT filter
- [ ] Migrate cert-vault JWT filter
- [ ] Verify ai-service JWT filter (if present)
- [ ] Leave gateway unchanged (reactive)

---

### Step 3: `StructuredTaskScope` in Admin-API

**File to modify:** `routify-admin-api/.../service/DashboardStatsService.java`

**Before (sequential):**
```java
public DashboardStats getStats(UUID tenantId) {
    var routeStats = routeServiceClient.getRouteStats(tenantId);    // ~40ms
    var auditStats = auditMessagingClient.getRequestStats(tenantId); // ~40ms
    var certStats  = certVaultClient.getCertStats(tenantId);         // ~40ms
    var aiStats    = aiMessagingClient.getAiFilterStats(tenantId);   // ~40ms
    // Total: ~160ms sequential
    return new DashboardStats(routeStats, auditStats, certStats, aiStats);
}
```

**After (structured concurrency):**
```java
public DashboardStats getStats(UUID tenantId) throws InterruptedException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var routeStats = scope.fork(() -> routeServiceClient.getRouteStats(tenantId));
        var auditStats = scope.fork(() -> auditMessagingClient.getRequestStats(tenantId));
        var certStats  = scope.fork(() -> certVaultClient.getCertStats(tenantId));
        var aiStats    = scope.fork(() -> aiMessagingClient.getAiFilterStats(tenantId));

        scope.join().throwIfFailed();  // ~40ms parallel (max of all)

        return new DashboardStats(
            routeStats.get(), auditStats.get(), certStats.get(), aiStats.get());
    }
}
```

**Task list:**
- [ ] Convert `DashboardStatsService.getStats()` to `StructuredTaskScope`
- [ ] Convert `DashboardStatsService.getGatewayStatus()` if it has parallel calls
- [ ] Add timeout: `scope.joinUntil(Instant.now().plusSeconds(5))`
- [ ] Handle partial failure: `ShutdownOnFailure` cancels remaining tasks on first error

---

### Step 4: `StructuredTaskScope` in AI Service

**File to modify:** `routify-ai-service/.../service/AiFilterEvaluationService.java`

**Pattern: parallel cache check + LLM evaluation:**
```java
public RouteEvaluationResponse evaluate(RouteEvaluationRequest request) {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<RouteEvaluationResponse>()) {
        // Race: cache hit vs LLM call — first to complete wins
        scope.fork(() -> verdictCacheService.getCached(request));
        scope.fork(() -> callLlm(request));

        scope.join();
        return scope.result();
    }
}
```

> **Note:** `ShutdownOnSuccess` cancels the slower task when the first succeeds — perfect for the "cache or compute" pattern.

**Task list:**
- [ ] Implement cache-vs-LLM race pattern
- [ ] Verify cancellation of LLM call when cache hits
- [ ] Add metrics for cache-hit vs LLM-complete winner

---

### Step 5: Kafka Consumer `ScopedValue` Propagation

Kafka consumers currently use `SecurityContext.putMdc()` for MDC. Since consumers don't have a full `SecurityContext` (no JWT — it's a system-to-system call), create a lightweight scoped value for consumer context:

```java
// In Kafka consumer before processing:
var ctx = new SecurityContext(
    UUID.fromString(headers.get("X-User-Id")),
    UUID.fromString(headers.get("X-Tenant-Id")),
    "system",
    "SYSTEM",
    headers.get("X-Correlation-Id")
);
ScopedValue.runWhere(SecurityContext.SCOPE, ctx, () -> {
    processCommand(record);
});
```

**Task list:**
- [ ] Update all Kafka consumers in route-service, identity-service, cert-vault, audit-service
- [ ] Verify MDC is still populated for log correlation

---

## Acceptance Criteria

- [ ] `SecurityContext` uses `ScopedValue` — zero `ThreadLocal` instances in the codebase
- [ ] `DashboardStatsService.getStats()` executes 4 RPC calls in parallel (~40ms vs ~160ms)
- [ ] Child virtual threads in `StructuredTaskScope` can read `SecurityContext.SCOPE.get()`
- [ ] Gateway (reactive) is unaffected — still uses reactor context
- [ ] All existing unit and integration tests pass
- [ ] MDC correlation IDs still appear in structured logs

