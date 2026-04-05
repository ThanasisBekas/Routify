# Initiative Q2-04 — String Template Migration

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 4–6 · **Owner:** All services

---

## Problem Statement

The codebase uses three different string construction patterns:
1. `"Resource '%s' not found".formatted(id)` — ~20+ sites (exception messages, audit logs)
2. `"prefix" + variable + "suffix"` — ~20+ sites (Redis keys, log messages, test assertions)
3. `String.format("...", args)` — occasional

This inconsistency hurts readability. Java 25 string templates (`"...\{expr}..."`) unify all patterns into one type-safe, inline syntax.

---

## Detailed Implementation Steps

### Step 1: Exception Message Templates

**Scope:** `RoutifyException` subtype constructors and all `throw new RoutifyException.*()` call sites.

**Before:**
```java
throw new RoutifyException.Conflict("Username '%s' already exists".formatted(request.username()));
throw new RoutifyException.NotFound("%s with id '%s' not found".formatted(resource, id));
```

**After:**
```java
throw new RoutifyException.Conflict("Username '\{request.username()}' already exists");
throw new RoutifyException.NotFound("\{resource} with id '\{id}' not found");
```

**Files to modify:**
- `routify-common/.../exception/RoutifyException.java` — `NotFound`, `RateLimitExceeded`, `QuotaExceeded` constructors
- `routify-identity-service/.../service/UserService.java` — ~6 sites
- `routify-identity-service/.../service/TenantService.java` — ~4 sites
- `routify-route-service/.../service/RouteService.java`
- `routify-cert-vault/.../service/CertificateService.java`
- `routify-cert-vault/.../service/CertGroupService.java`

**Task list:**
- [ ] Convert all `RoutifyException` constructor messages to string templates
- [ ] Convert all `throw new RoutifyException.*()` call sites

---

### Step 2: Log Message Concatenation

**Rule:** SLF4J's `log.info("msg {}", arg)` placeholder syntax stays — it avoids string allocation when the log level is disabled. Only convert explicit `+` concatenation in log calls.

**Before:**
```java
log.error("Failed to serialize domain event to outbox: " + event.getClass().getSimpleName(), e);
log.warn("Unknown domain event type on topic [" + topic + "]");
log.info("Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + "s");
```

**After:**
```java
log.error("Failed to serialize domain event to outbox: \{event.getClass().getSimpleName()}", e);
log.warn("Unknown domain event type on topic [\{topic}]");
log.info("Kafka send timed out after \{KAFKA_SEND_TIMEOUT_SECONDS}s");
```

**Task list:**
- [ ] Search for `log.*(.*" + ` patterns
- [ ] Convert concatenation to string templates
- [ ] Do NOT touch SLF4J `{}` placeholders

---

### Step 3: Redis Key Construction

**Before:**
```java
redisTemplate.delete(RedisKeys.APIKEY_PREFIX + rawKey);
redisTemplate.opsForHash().putAll(RedisKeys.APIKEY_PREFIX + rawKey, map);
```

**After:**
```java
redisTemplate.delete("\{RedisKeys.APIKEY_PREFIX}\{rawKey}");
redisTemplate.opsForHash().putAll("\{RedisKeys.APIKEY_PREFIX}\{rawKey}", map);
```

**Task list:**
- [ ] Convert Redis key constructions in gateway filters
- [ ] Convert Redis key constructions in identity-service

---

### Step 4: Test Assertion Strings

**Before:**
```java
assertThat(json).contains("\"type\":\"" + expectedType + "\"");
```

**After:**
```java
assertThat(json).contains("\"type\":\"\{expectedType}\"");
```

**Task list:**
- [ ] Convert test assertion string concatenation across all test modules

---

### Step 5: Kafka Topic DLQ Derivation

**Before (in `KafkaTopics.java`):**
```java
public static final String DLQ_ROUTE_EVENTS = ROUTE_EVENTS + ".DLQ";
```

**Decision:** Keep `+` for compile-time constants (`static final`). String templates produce `String` values that can be `static final`, but the template expression must be a constant expression — `ROUTE_EVENTS + ".DLQ"` is already a constant fold. Converting to `"\{ROUTE_EVENTS}.DLQ"` is equivalent but verify it remains a compile-time constant.

**Task list:**
- [ ] Evaluate if `KafkaTopics` DLQ derivations benefit from templates
- [ ] Convert only if readability improves and compile-time constant status is preserved

---

### Step 6: Do NOT Template-ize

- **SQL / JPQL strings** — SQL injection risk. Continue using parameterized queries.
- **SLF4J `{}` placeholders** — SLF4J's lazy evaluation is more efficient than eager template interpolation.
- **Regex patterns** — `Pattern.compile()` strings should not use templates.

**Task list:**
- [ ] Verify no SQL strings were accidentally template-ized
- [ ] Add a code review checklist item: "No string templates in SQL or regex"

---

## Acceptance Criteria

- [ ] Zero `String.formatted()` calls in production code (test code is optional)
- [ ] Zero `"" + variable + ""` concatenation in production code outside of compile-time constants
- [ ] All string templates compile correctly
- [ ] SLF4J `{}` placeholders are preserved
- [ ] No string templates in SQL, JPQL, or regex strings
- [ ] All existing tests pass

