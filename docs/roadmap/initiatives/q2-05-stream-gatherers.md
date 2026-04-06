# Initiative Q2-05 — Stream Gatherers & Modern Collection Patterns

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 5–7 · **Owner:** All services

---

## Problem Statement

The codebase uses `Collectors.toList()` (12 sites), `Collectors.toUnmodifiableList()` (3 sites), `Collections.unmodifiableMap/List()` (4 sites), and `list.get(0)` patterns that have cleaner modern equivalents. Stream Gatherers (`Stream.gather()`) — finalized in Java 25 — are not used anywhere, missing opportunities for custom intermediate stream operations.

---

## Detailed Implementation Steps

### Step 1: `Collectors.toList()` → `.toList()`

`.toList()` (since JDK 16) returns an unmodifiable list and is more concise.

**Sites to convert (~12):**
```java
// Before:
.collect(Collectors.toList())
// After:
.toList()
```

**Files:**
- `routify-common/.../config/SecretValidator.java` (2 sites)
- `routify-api-gateway/.../auth/util/AuthenticationUtils.java`
- `routify-api-gateway/.../filter/RequestLoggerGatewayFilterFactory.java`
- `routify-api-gateway/.../filter/AiGatewayFilterFactory.java`
- `routify-api-gateway/.../filter/AiModifierGatewayFilterFactory.java`
- `routify-api-gateway/.../filter/JsonSchemaValidateGatewayFilterFactory.java` (2 sites)

**Note:** `.collect(Collectors.toMap(...))` stays — there is no `.toMap()` shortcut.

**Task list:**
- [ ] Global find-and-replace `.collect(Collectors.toList())` → `.toList()`
- [ ] Remove unused `import java.util.stream.Collectors` where it was the only usage
- [ ] Verify no code relies on the returned list being mutable (`.toList()` returns unmodifiable)

---

### Step 2: `Collectors.toUnmodifiableList()` → `.toList()`

Already equivalent since JDK 16.

**Sites:** `routify-api-gateway/.../routing/RouteRefreshAuditLogger.java` (2 sites using `Collectors.toUnmodifiableSet()` — keep those as `.collect(Collectors.toUnmodifiableSet())` since there's no `.toSet()` shortcut that guarantees unmodifiability).

**Task list:**
- [ ] Convert `Collectors.toUnmodifiableList()` → `.toList()` where applicable
- [ ] Keep `Collectors.toUnmodifiableSet()` (no shortcut)

---

### Step 3: `Collections.unmodifiable*` → Immutable Factories

**Before:**
```java
public Map<String, Object> getConfig() { return Collections.unmodifiableMap(config); }
public List<RouteFilter> getFilters() { return Collections.unmodifiableList(filters); }
```

**After:**
```java
public Map<String, Object> getConfig() { return Map.copyOf(config); }
public List<RouteFilter> getFilters() { return List.copyOf(filters); }
```

> `Map.copyOf()` / `List.copyOf()` create truly immutable snapshots (not just unmodifiable views of the original mutable collection).

**Sites:**
- `routify-route-service/.../domain/Route.java` (2 sites)
- `routify-route-service/.../domain/FilterDefinition.java` (1 site)
- `routify-api-gateway/.../certificate/CertificateRegistry.java` (1 site)

**Task list:**
- [ ] Convert `Collections.unmodifiableMap()` → `Map.copyOf()`
- [ ] Convert `Collections.unmodifiableList()` → `List.copyOf()`
- [ ] Verify no null values in the source collections (`copyOf` throws on null)

---

### Step 4: Sequenced Collection Methods

**`list.get(0)` → `list.getFirst()`:**
```java
// Clearer intent:
var firstRoute = routes.getFirst();
var lastEntry  = auditEntries.getLast();
```

**`list.reversed()`** — useful for reverse-chronological iteration:
```java
// Audit entries in reverse order:
for (var entry : entries.reversed()) { ... }
```

**Task list:**
- [ ] Search for `.get(0)` → replace with `.getFirst()` where applicable
- [ ] Search for `.get(list.size() - 1)` → replace with `.getLast()`
- [ ] Use `.reversed()` where reverse iteration is currently done via `Collections.reverse()` copy

---

### Step 5: Custom Stream Gatherer — Sliding Window Metrics

**Use case:** In `DashboardStatsService` or audit-service query handlers, compute rolling-window statistics from a stream of request log entries.

**Implementation:**
```java
// Custom gatherer: partition a sorted stream into fixed-size time windows
Gatherer<RequestLogEntry, ?, List<RequestLogEntry>> windowByHour() {
    return Gatherer.ofSequential(
        () -> new Object() { Instant windowStart = null; List<RequestLogEntry> buffer = new ArrayList<>(); },
        (state, element, downstream) -> {
            var hour = element.requestedAt().truncatedTo(ChronoUnit.HOURS);
            if (state.windowStart == null) state.windowStart = hour;
            if (!hour.equals(state.windowStart)) {
                downstream.push(List.copyOf(state.buffer));
                state.buffer.clear();
                state.windowStart = hour;
            }
            state.buffer.add(element);
            return true;
        },
        (state, downstream) -> {
            if (!state.buffer.isEmpty()) downstream.push(List.copyOf(state.buffer));
        }
    );
}

// Usage:
var hourlyBuckets = requestLogs.stream()
    .sorted(Comparator.comparing(RequestLogEntry::requestedAt))
    .gather(windowByHour())
    .map(bucket -> computeStats(bucket))
    .toList();
```

**Task list:**
- [ ] Implement `windowByHour()` gatherer for audit query handlers
- [ ] Add unit test for gatherer with edge cases (empty stream, single element, boundary)
- [ ] Evaluate if other stream processing patterns benefit from custom gatherers

---

### Step 6: Custom Stream Gatherer — Batch Delete

**Use case:** `AuditRetentionScheduler.deleteInBatches()` currently uses a while-loop. A gatherer can partition a stream into fixed-size batches for deletion:

```java
// Conceptual — the current code is already efficient with its BiFunction pattern.
// Only convert if it improves readability. Evaluate and decide.
```

**Task list:**
- [ ] Evaluate if batch-delete benefits from gatherer pattern
- [ ] Implement only if readability improves over current while-loop

---

## Acceptance Criteria

- [ ] Zero `Collectors.toList()` calls — all replaced with `.toList()`
- [ ] `Collections.unmodifiable*` replaced with `Map.copyOf()` / `List.copyOf()` where safe
- [ ] `getFirst()` / `getLast()` used instead of `get(0)` / `get(size-1)`
- [ ] At least one custom `Gatherer` implemented for time-window bucketing
- [ ] All existing tests pass

