# Initiative Q2-03 — Pattern Matching & Exhaustive Switch Audit

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 3–5 · **Owner:** All services

---

## Problem Statement

The codebase uses `switch` expressions on sealed types (`CommandEvent`, `DomainEvent`) but many have `default` branches that swallow unknown types at runtime instead of catching them at compile time. Record patterns and unnamed variables are not used anywhere. This leaves type-safety on the table and reduces readability.

---

## Detailed Implementation Steps

### Step 1: Exhaustive Switch on `CommandEvent`

**Files:** All Kafka command consumers across services.

**Before:**
```java
switch (cmd) {
    case CommandEvent.CreateRoute c  -> routeService.create(c);
    case CommandEvent.UpdateRoute c  -> routeService.update(c);
    // ... many cases ...
    default -> log.warn("Unknown command: {}", cmd.getClass().getSimpleName());
}
```

**After (exhaustive — no `default`):**
```java
switch (cmd) {
    case CommandEvent.CreateRoute c  -> routeService.create(c);
    case CommandEvent.UpdateRoute c  -> routeService.update(c);
    // ... all cases listed ...
    case CommandEvent.Unknown _      -> log.warn("Unknown command type received");
}
```

> When a new `CommandEvent` subtype is added, the compiler will force every consumer to add a case — no more silent drops.

**Files to modify:**
- `routify-route-service/.../messaging/RouteCommandKafkaConsumer.java`
- `routify-identity-service/.../messaging/UserCommandKafkaConsumer.java`
- `routify-identity-service/.../messaging/AuthCommandKafkaConsumer.java`
- `routify-cert-vault/.../messaging/CertCommandConsumer.java` (or equivalent)

**Task list:**
- [ ] Make all `CommandEvent` switches exhaustive
- [ ] Replace `default` with explicit `case CommandEvent.Unknown _`
- [ ] Use unnamed variable `_` where the binding is not accessed

---

### Step 2: Exhaustive Switch on `DomainEvent`

**Files:**
- `routify-audit-service/.../consumer/DomainEventAuditConsumer.java` — `resolveEventType()`, `resolveAggregateId()`, `resolveAggregateType()`
- `routify-route-service/.../outbox/OutboxEventStore.java` — `resolveTargetTopic()`, `resolvePartitionKey()`
- `routify-identity-service/.../outbox/IdentityOutboxEventStore.java`
- `routify-cert-vault/.../outbox/CertOutboxEventStore.java`

**Task list:**
- [ ] Make all `DomainEvent` switches exhaustive
- [ ] Use unnamed variable `_` for unused bindings

---

### Step 3: Record Patterns in Switch

**Before:**
```java
case CommandEvent.CreateRoute c -> {
    routeService.create(c.tenantId(), c.name(), c.pathPattern(), c.upstreamUri());
}
```

**After (record pattern with destructuring):**
```java
case CommandEvent.CreateRoute(var cmdId, var tenantId, _, _, var name, _, var path, _, var upstream, _, _) -> {
    routeService.create(tenantId, name, path, upstream);
}
```

> Use `_` for all unused components. This makes it immediately visible which fields matter for each case.

**Apply selectively** — only where destructuring improves readability (3+ fields used). For single-field access, `case CreateRoute c -> c.name()` is still cleaner.

**Task list:**
- [ ] Apply record patterns in route command consumer (most fields used)
- [ ] Apply record patterns in identity command consumer
- [ ] Apply record patterns in outbox event stores
- [ ] Skip record patterns where only 1-2 fields are accessed (use named binding instead)

---

### Step 4: Unnamed Variables in Catch Blocks & Lambdas

**Pattern 1: Unused exception variable**
```java
// Before:
catch (Exception e) { log.error("Operation failed"); }
// After:
catch (Exception _) { log.error("Operation failed"); }
```

**Pattern 2: Unused lambda parameters**
```java
// Before:
map.computeIfAbsent(key, k -> new ArrayList<>());
// After:
map.computeIfAbsent(key, _ -> new ArrayList<>());
```

**Pattern 3: Unused for-each variable**
```java
// Before:
for (var entry : results) { count++; }
// After:
for (var _ : results) { count++; }
```

**Task list:**
- [ ] Global search for `catch (Exception e)` where `e` is unused → replace with `_`
- [ ] Search for lambda parameters that are unused → replace with `_`
- [ ] Run compile to verify all `_` usages are valid

---

### Step 5: Pattern Matching for `instanceof` Cleanup

The codebase already uses pattern matching for `instanceof` (Java 16+). Verify all sites are consistent:

```java
// Already correct:
if (value instanceof Boolean b) return b;
if (cert instanceof X509Certificate x509) { ... }

// Find any remaining old-style:
if (foo instanceof Bar) { Bar bar = (Bar) foo; ... }
// Convert to:
if (foo instanceof Bar bar) { ... }
```

**Task list:**
- [ ] Search for `instanceof` followed by cast on next line
- [ ] Convert all to pattern-matching `instanceof`
- [ ] Verify no remaining non-pattern `instanceof` casts

---

## Acceptance Criteria

- [ ] Zero `default` branches on `switch` over sealed types (`CommandEvent`, `DomainEvent`)
- [ ] All sealed-type switches are compiler-verified exhaustive
- [ ] Record patterns used in ≥5 switch cases where 3+ fields are destructured
- [ ] Unnamed variables `_` used for all genuinely unused bindings
- [ ] All existing tests pass (no behavioral change)

