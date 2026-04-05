# Routify — Q2 2026 Remainder Roadmap: Java 25 Platform Modernization

> **Period:** April 7 – June 27, 2026 (remaining ~12 weeks of Q2)  
> **Platform version:** 2.0.0-SNAPSHOT → **2.0.0** release at end of Q2  
> **Baseline:** Java 25 (`<java.version>25</java.version>`) already set in parent POM; Spring Boot 3.5.13; Dockerfiles use `eclipse-temurin:25-jdk-alpine` / `eclipse-temurin:25-jre-alpine`; Virtual Threads enabled on all non-reactive services.  
> **Goal:** Systematically adopt every production-ready Java 25 feature across the entire Routify platform — improving performance, readability, safety, and maintainability — culminating in a clean 2.0.0 GA release.

---

## Java 25 Feature Adoption Inventory

The following table maps each stable Java 25 feature to its adoption status in the Routify codebase and the initiative that addresses it.

| Java Feature | JEP | Status in Routify | Initiative |
|---|---|---|---|
| **Records** (JDK 16+) | 395 | ✅ Used extensively (`CommandEvent`, `SecurityContext`, `PageResponse`, `QueryRequest/Response`) | Extend: [Init 2](#2-record-migration-for-dtos--value-objects) |
| **Sealed classes** (JDK 17+) | 409 | ✅ Used (`CommandEvent` sealed interface, `RoutifyException` sealed class, `DomainEvent`) | Extend: [Init 2](#2-record-migration-for-dtos--value-objects) |
| **Pattern matching for `instanceof`** (JDK 16+) | 394 | ✅ Used in gateway filters | Audit: [Init 3](#3-pattern-matching--exhaustive-switch-audit) |
| **Pattern matching for `switch`** (JDK 21+) | 441 | ✅ Partially used in command consumers | Extend: [Init 3](#3-pattern-matching--exhaustive-switch-audit) |
| **Virtual Threads** (JDK 21+) | 444 | ✅ Enabled (`spring.threads.virtual.enabled: true`) on all non-reactive services | Deepen: [Init 1](#1-structured-concurrency--scoped-values) |
| **Structured Concurrency** (JDK 25 stable) | 505 | ❌ Not used — `CompletableFuture` and single-threaded flows only | **New:** [Init 1](#1-structured-concurrency--scoped-values) |
| **Scoped Values** (JDK 25 stable) | 507 | ❌ Not used — `SecurityContext` uses `ThreadLocal` | **New:** [Init 1](#1-structured-concurrency--scoped-values) |
| **String Templates** (JDK 25 stable) | 501 | ❌ Not used — uses `String.formatted()` and `"" + ""` concatenation | **New:** [Init 4](#4-string-template-migration) |
| **Stream Gatherers** (JDK 25 stable) | 485 | ❌ Not used — standard `Collectors` only | **New:** [Init 5](#5-stream-gatherers--modern-collection-patterns) |
| **Sequenced Collections** (JDK 21+) | 431 | ❌ Not used — `getFirst()` / `getLast()` / `reversed()` not called | **New:** [Init 5](#5-stream-gatherers--modern-collection-patterns) |
| **Record patterns** (JDK 21+) | 440 | ❌ Not used in destructuring | **New:** [Init 3](#3-pattern-matching--exhaustive-switch-audit) |
| **Unnamed variables** (JDK 22+) | 456 | ❌ Not used — `_` for unused params | **New:** [Init 3](#3-pattern-matching--exhaustive-switch-audit) |
| **Unnamed patterns** (JDK 22+) | 456 | ❌ Not used | **New:** [Init 3](#3-pattern-matching--exhaustive-switch-audit) |
| **`StructuredTaskScope`** (JDK 25 stable) | 505 | ❌ Not used | **New:** [Init 1](#1-structured-concurrency--scoped-values) |
| **Compact `module-info`** considerations | — | N/A (classpath-based Spring Boot app) | Skip — JPMS migration is out of scope |
| **ZGC by default** (JDK 25) | — | ✅ Already set: `-XX:+UseZGC` in all Dockerfiles | Verify: [Init 6](#6-jvm-runtime--docker-optimization) |
| **Compact Object Headers** (JDK 25 experimental) | — | ❌ Not enabled | **New:** [Init 6](#6-jvm-runtime--docker-optimization) |
| **Foreign Function & Memory API** (JDK 22+) | 454 | N/A — no native interop needs currently | Skip |
| **Class-File API** (JDK 24+) | 484 | N/A — no bytecode manipulation | Skip |

---

## Roadmap Summary

| # | Initiative | Theme | Weeks | Impact |
|---|-----------|-------|-------|--------|
| 1 | [Structured Concurrency & Scoped Values](#1-structured-concurrency--scoped-values) | Concurrency | 1–4 | Replace `ThreadLocal` + `CompletableFuture` with type-safe, virtual-thread-native APIs |
| 2 | [Record Migration for DTOs & Value Objects](#2-record-migration-for-dtos--value-objects) | Data Modeling | 2–4 | Replace remaining Lombok `@Data`/`@Value` DTOs with records; reduce annotation processing |
| 3 | [Pattern Matching & Exhaustive Switch Audit](#3-pattern-matching--exhaustive-switch-audit) | Type Safety | 3–5 | Adopt record patterns, unnamed variables, exhaustive `switch` everywhere |
| 4 | [String Template Migration](#4-string-template-migration) | Readability | 4–6 | Replace `String.formatted()`, concatenation, and `MessageFormat` with string templates |
| 5 | [Stream Gatherers & Modern Collections](#5-stream-gatherers--modern-collection-patterns) | API Modernization | 5–7 | Adopt `Stream.gather()`, sequenced collections, and `toList()` shortcuts |
| 6 | [JVM Runtime & Docker Optimization](#6-jvm-runtime--docker-optimization) | Performance | 6–8 | Compact Object Headers, CDS archives, Dockerfile base image refresh |
| 7 | [Codebase Cleanup & Deprecated API Removal](#7-codebase-cleanup--deprecated-api-removal) | Maintenance | 8–10 | Remove deprecated `FilterType` values, dead code, Lombok where records suffice |
| 8 | [2.0.0 GA Release Hardening](#8-200-ga-release-hardening) | Release | 10–12 | Final test pass, documentation update, `AGENTS.md` refresh, version bump to `2.0.0` |

Each initiative has a dedicated design document in [`docs/roadmap/initiatives/`](./initiatives/).

---

## 1. Structured Concurrency & Scoped Values

**Design doc:** [`initiatives/q2-01-structured-concurrency.md`](./initiatives/q2-01-structured-concurrency.md)

### Description
Replace the `ThreadLocal`-based `SecurityContext` with `ScopedValue` for virtual-thread safety. Introduce `StructuredTaskScope` in the admin-api's `DashboardStatsService` and `AiFilterEvaluationService` for parallel fan-out queries that propagate scoped values and handle partial failures cleanly.

### Expected Impact
- **Virtual-thread correctness** — `ScopedValue` is immutable and inherited by child virtual threads automatically, eliminating the `ThreadLocal` leak risk that plagues virtual thread pools.
- **Cleaner parallel queries** — `StructuredTaskScope.ShutdownOnFailure` replaces ad-hoc `CompletableFuture.allOf()` patterns with structured lifetime management.
- **Observability** — scoped values carry `correlationId` through forked tasks without manual MDC propagation.

### High-Level Implementation Plan
1. **`SecurityContext` → `ScopedValue`** — change `private static final ThreadLocal<SecurityContext> HOLDER` to `public static final ScopedValue<SecurityContext> SCOPE = ScopedValue.newInstance()`. Update `set()` → `ScopedValue.runWhere()`, `current()` → `SCOPE.get()`, `clear()` → no-op (scoped values are auto-scoped). Update all JWT filters in all services.
2. **Admin-api parallel queries** — `DashboardStatsService.getStats()` currently calls 4 RabbitMQ services sequentially. Wrap in `StructuredTaskScope.ShutdownOnFailure` to fan out all 4 queries as child virtual threads, join with timeout, collect results.
3. **AI service evaluation** — `AiFilterEvaluationService.evaluateAsync()` returns a trivial `CompletableFuture.completedFuture()`. Replace with `StructuredTaskScope` for parallel cache-check + LLM-call with structured cancellation.
4. **MDC propagation** — `ScopedValue` + `StructuredTaskScope` automatically inherit scoped values into child threads. Remove manual `MDC.put()` in forked contexts.

---

## 2. Record Migration for DTOs & Value Objects

**Design doc:** [`initiatives/q2-02-record-migration.md`](./initiatives/q2-02-record-migration.md)

### Description
Audit all services for Lombok `@Data`, `@Value`, `@Getter`/`@Setter` DTOs and value objects that can be replaced with Java records. Records provide immutability, `equals`/`hashCode`/`toString` by default, and eliminate the Lombok annotation processor dependency for those classes.

### Expected Impact
- **Reduced build complexity** — fewer classes that need Lombok annotation processing; faster incremental builds.
- **Immutability by default** — records are inherently immutable, preventing accidental mutation bugs in DTOs passed across service boundaries.
- **IDE-friendly** — records have first-class IDE support (decompilers, debuggers, refactoring tools) without Lombok plugin quirks.

### High-Level Implementation Plan
1. **Inventory** — scan all modules for `@Data`, `@Value`, and `@Getter/@Setter` classes that are pure data carriers (no inheritance, no mutable state needed).
2. **routify-common DTOs** — convert `PageResponse` (already a record), `AsyncAcknowledgement`, `RequestTelemetryEvent`, `AiFilterDecisionEvent`, `AiModificationDecisionEvent` — verify all are records; convert any that aren't.
3. **Service DTOs** — convert per-service DTO classes in `routify-admin-api/dto/`, `routify-route-service/dto/`, `routify-identity-service/dto/`, `routify-audit-service/` response DTOs, `routify-cert-vault/dto/`, `routify-ai-service/dto/`.
4. **JPA entities stay as classes** — entities with `@Entity`, `@Table`, `@Id` annotations remain Lombok-annotated classes (JPA requires mutable proxies). Do NOT convert entities to records.
5. **MapStruct compatibility** — verify MapStruct 1.6.3 generates correct mapping code for record sources/targets (it does — `componentModel = "spring"`).
6. **Sealed DTO hierarchies** — where a family of related DTOs exists (e.g., gateway config section DTOs), convert to sealed interface + record subtypes.

---

## 3. Pattern Matching & Exhaustive Switch Audit

**Design doc:** [`initiatives/q2-03-pattern-matching.md`](./initiatives/q2-03-pattern-matching.md)

### Description
Systematically adopt record patterns in `switch` expressions, unnamed variables (`_`) for unused bindings, and ensure all `switch` over sealed types are exhaustive (no `default` branch — the compiler enforces completeness).

### Expected Impact
- **Compile-time safety** — adding a new `CommandEvent` or `DomainEvent` subtype forces all `switch` consumers to handle it, preventing runtime `Unknown` fallbacks.
- **Readability** — record patterns destructure directly: `case CreateRoute(var id, var tenant, _, _, var name, ...)` instead of `case CreateRoute c -> c.name()`.
- **Reduced noise** — `_` replaces dummy variable names in catch blocks, lambdas, and pattern bindings.

### High-Level Implementation Plan
1. **Command consumers** — `RouteCommandKafkaConsumer`, `UserCommandKafkaConsumer`, `AuthCommandKafkaConsumer`, `CertCommandConsumer` — convert `switch (cmd)` to exhaustive pattern-matching `switch` with record patterns. Remove `default` branches.
2. **Domain event consumers** — `DomainEventAuditConsumer.resolveEventType()`, `resolveAggregateId()`, `resolveAggregateType()` — already use `switch` expressions on `DomainEvent`; ensure exhaustive, add record patterns.
3. **Outbox event stores** — `OutboxEventStore`, `IdentityOutboxEventStore`, `CertOutboxEventStore` — `resolveTargetTopic()` and `resolvePartitionKey()` use `switch` on `DomainEvent`; make exhaustive.
4. **Gateway filter factories** — `instanceof` checks in `SecurityHeadersGatewayFilterFactory`, `GlobalSecurityHeadersFilter` — convert to pattern-matching `switch` where applicable.
5. **Unnamed variables** — replace `catch (Exception e) { log.error(...); }` where `e` is unused with `catch (Exception _)`. Replace unused lambda params with `_` (e.g., `(key, _) -> ...`).

---

## 4. String Template Migration

**Design doc:** [`initiatives/q2-04-string-templates.md`](./initiatives/q2-04-string-templates.md)

### Description
Replace `String.formatted()`, `"" + variable + ""` concatenation, and `String.format()` calls with Java 25 string templates (`"...\{expression}..."`) across all services. String templates are type-safe, more readable, and support multi-line interpolation natively.

### Expected Impact
- **Readability** — `"Route %s not found".formatted(id)` becomes `"Route \{id} not found"` — inline, no positional `%s` matching.
- **Safety** — the compiler verifies that template expressions are valid at compile time.
- **Consistency** — eliminates three different string construction patterns (`formatted`, `+`, `format`) in favor of one.

### High-Level Implementation Plan
1. **Exception messages** — `RoutifyException` subtype constructors use `"...".formatted(...)` — convert to string templates. ~20 call sites across identity-service, route-service, cert-vault.
2. **Log messages** — SLF4J `log.info("...", arg)` stays as-is (SLF4J has its own `{}` interpolation). Only convert `log.error("Failed: " + e.getMessage())` patterns to template literals.
3. **Kafka/RabbitMQ keys** — `RedisKeys` prefix constructions (`APIKEY_PREFIX + rawKey`), Kafka topic DLQ derivations (`topic + ".DLQ"`) — convert to templates.
4. **Test assertions** — `assertThat(json).contains("\"type\":\"" + expectedType + "\"")` — convert to templates.
5. **SQL / JPQL** — do NOT template-ize SQL strings (SQL injection risk). Only use templates for log messages about SQL operations.

---

## 5. Stream Gatherers & Modern Collection Patterns

**Design doc:** [`initiatives/q2-05-stream-gatherers.md`](./initiatives/q2-05-stream-gatherers.md)

### Description
Adopt `Stream.gather()` (JDK 25 final) for custom intermediate operations, replace `Collectors.toList()` / `Collectors.toUnmodifiableList()` with `.toList()`, and use sequenced collection methods (`getFirst()`, `getLast()`, `reversed()`) where applicable.

### Expected Impact
- **Cleaner pipelines** — `gather()` replaces multi-step `collect` + post-processing patterns with single-pass custom gatherers (e.g., sliding windows, partitioning, running statistics).
- **Conciseness** — `.collect(Collectors.toList())` → `.toList()` across ~12 call sites.
- **Semantic intent** — `list.getFirst()` is clearer than `list.get(0)`.

### High-Level Implementation Plan
1. **`Collectors.toList()` → `.toList()`** — global search-and-replace for `.collect(Collectors.toList())` patterns (~12 sites in routify-common, api-gateway, admin-api).
2. **`Collectors.toUnmodifiableList()` → `.toList()`** — `.toList()` already returns an unmodifiable list since JDK 16.
3. **Sequenced Collections** — replace `list.get(0)` → `list.getFirst()`, `list.get(list.size()-1)` → `list.getLast()` where applicable.
4. **Custom gatherer: sliding window metrics** — in `DashboardStatsService`, implement a `Gatherer` for computing rolling-window request statistics from a stream of telemetry events (replace the current loop-based computation).
5. **Custom gatherer: batch processing** — in `AuditRetentionScheduler.deleteInBatches()`, consider a `Gatherer.ofSequential()` for partitioning delete operations into fixed-size batches from a stream source.
6. **`Collections.unmodifiableMap/List()` → `Map.copyOf()` / `List.copyOf()`** — replace ~4 sites in `Route`, `FilterDefinition`, `CertificateRegistry`.

---

## 6. JVM Runtime & Docker Optimization

**Design doc:** [`initiatives/q2-06-jvm-optimization.md`](./initiatives/q2-06-jvm-optimization.md)

### Description
Optimize JVM runtime flags for Java 25, enable Compact Object Headers (experimental but stable for reducing heap footprint by ~8%), create CDS (Class Data Sharing) archives for faster startup, and refresh Docker base images.

### Expected Impact
- **Faster startup** — CDS archives reduce class loading time by ~30%, critical for the 7-service fleet.
- **Lower memory** — Compact Object Headers reduce per-object overhead from 12 bytes to 8 bytes, saving ~5-10% heap on entity-heavy services (audit-service, route-service).
- **Smaller images** — Alpine-based JRE images + jlink custom runtime (optional) reduces image size.

### High-Level Implementation Plan
1. **Compact Object Headers** — add `-XX:+UseCompactObjectHeaders` to `JAVA_OPTS` in all Dockerfiles. Monitor with `-XX:+PrintFlagsFinal` to verify enablement.
2. **CDS archive generation** — two-stage Dockerfile: (a) training run with `-XX:ArchiveClassesAtExit=app-cds.jsa -Dspring.context.exit=onRefresh`; (b) production run with `-XX:SharedArchiveFile=app-cds.jsa`. Reduces startup from ~4s to ~2.5s per service.
3. **ZGC tuning** — already using `-XX:+UseZGC`; add `-XX:+ZGenerational` (generational ZGC, default in JDK 25) for improved throughput on the gateway's short-lived request objects.
4. **Docker base image audit** — verify all Dockerfiles use `eclipse-temurin:25-jre-alpine`; add `JAVA_TOOL_OPTIONS` for container-aware GC ergonomics.
5. **Startup probes** — reduce `start_period` in `docker-compose.app.yml` healthchecks from 30s to 15s after CDS optimization.

---

## 7. Codebase Cleanup & Deprecated API Removal

**Design doc:** [`initiatives/q2-07-cleanup.md`](./initiatives/q2-07-cleanup.md)

### Description
Remove deprecated code, unused imports, dead `FilterType` enum values, and legacy patterns that were kept for backward compatibility but are no longer needed in the 2.0.0 release.

### Expected Impact
- **Reduced surface area** — removing 12 deprecated `FilterType` values and their skip-logic in `RouteDefinitionBuilder` simplifies the gateway filter chain.
- **Cleaner builds** — removing unused Lombok annotations from classes converted to records reduces annotation processor load.
- **Migration-ready** — clean codebase makes Q3/Q4 features easier to build on.

### High-Level Implementation Plan
1. **Deprecated `FilterType` removal** — remove `AUTH_NONE`, `RATE_LIMIT_TOKEN_BUCKET`, `PATH_REWRITE`, `PATH_STRIP_PREFIX`, `PATH_ADD_PREFIX`, `QUERY_PARAM_MODIFY`, `BODY_JSONATA_TRANSFORM`, `BODY_SPEL_TRANSFORM`, `VALIDATE_REGEX`, `VALIDATE_SIZE`, `CIRCUIT_BREAKER`, `RETRY` from the enum. Add Flyway migration to update any DB rows referencing them (set to `null` or a replacement type). Update TypeScript `FilterType` union. Remove skip-warning logic in `RouteDefinitionBuilder`.
2. **Lombok audit** — for every class converted to a record in Init 2, remove the Lombok import and annotation. If a module has zero remaining Lombok usage, remove `lombok` from its POM `dependencies`.
3. **Dead code removal** — run IntelliJ "Unused declaration" inspection across all modules. Remove unreachable methods, unused private fields, commented-out code blocks.
4. **`Collections.unmodifiable*` → immutable factories** — final pass after Init 5 to catch any remaining sites.
5. **Suppress-warning audit** — review all `@SuppressWarnings` annotations; remove those that were suppressing warnings fixed by Java 25 improvements.

---

## 8. 2.0.0 GA Release Hardening

**Design doc:** [`initiatives/q2-08-release-hardening.md`](./initiatives/q2-08-release-hardening.md)

### Description
Final stabilization pass: run all unit + integration tests, update `AGENTS.md` with Java 25 features, update all `README.md` files, bump version to `2.0.0`, create release tag and Docker images.

### Expected Impact
- **Release confidence** — full test pass validates all Java 25 modernization changes.
- **Documentation accuracy** — `AGENTS.md` reflects actual codebase patterns for AI agents.
- **Clean release** — `2.0.0` tag marks the completion of the Java 25 migration.

### High-Level Implementation Plan
1. **Full test run** — `mvn verify -DskipITs=false` across all modules. Fix any test failures from Java 25 changes.
2. **Frontend regression** — `npm run test:ci` + `npm run test:e2e:ci` to verify no API contract changes broke the dashboard.
3. **`AGENTS.md` update** — document new patterns: `ScopedValue` instead of `ThreadLocal`, `StructuredTaskScope` for parallel queries, string templates, record patterns, `Stream.gather()`, updated JVM flags.
4. **Service `README.md` updates** — each service's README reflects Java 25-specific configuration and patterns.
5. **Version bump** — change `<version>2.0.0-SNAPSHOT</version>` → `<version>2.0.0</version>` in parent POM and all child modules. Update `routify-dashboard/package.json` version.
6. **Docker image build + push** — `mvn clean package -DskipTests && docker compose -f docker-compose.yml -f docker-compose.app.yml build`. Tag images as `2.0.0`.
7. **Release notes** — document all Java 25 changes, breaking changes (deprecated FilterType removal), and migration guide for anyone upgrading from 1.x.

---

## Dependency & Sequencing Map

```
Week 1   Week 2   Week 3   Week 4   Week 5   Week 6   Week 7   Week 8   Week 9   Week 10  Week 11  Week 12
──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ────────
█████████████████████████████████████                                                                 1. Structured Concurrency
         ████████████████████████████                                                                 2. Record Migration
                  ████████████████████████████                                                        3. Pattern Matching
                           ████████████████████████████                                               4. String Templates
                                    ████████████████████████████                                      5. Stream Gatherers
                                             ████████████████████████████                             6. JVM Optimization
                                                                       █████████████████████████████  7. Cleanup & Deprecation
                                                                                     ████████████████ 8. Release Hardening
```

### Key Dependencies
- **Init 2 (Records)** unblocks **Init 3 (Pattern Matching)** — record patterns require records.
- **Init 1 (Scoped Values)** must complete before **Init 8 (Release)** — it changes a cross-cutting concern (`SecurityContext`).
- **Init 4 (String Templates)** and **Init 5 (Gatherers)** are independent and can proceed in parallel.
- **Init 7 (Cleanup)** depends on all feature initiatives (1–6) being complete.
- **Init 8 (Release)** depends on everything.

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `ScopedValue` incompatibility with Spring Security filter chain | Medium | High | Prototype in a branch first; keep `ThreadLocal` fallback behind feature flag `routify.security.scoped-values.enabled` |
| String templates not yet supported by all IDE formatters | Low | Low | Gradual adoption; configure IntelliJ formatter to recognize template syntax |
| Compact Object Headers causing GC issues in production | Low | Medium | Enable only in staging first; monitor with `-Xlog:gc*` for 1 week before production |
| MapStruct 1.6.3 record mapping edge cases | Low | Medium | Run full MapStruct test suite after record migration; upgrade MapStruct if needed |
| Deprecated `FilterType` removal breaks existing database rows | Medium | High | Flyway migration maps deprecated values to `null` with a warning log; run migration on staging first |
| `StructuredTaskScope` thread dump readability | Low | Low | The JDK 25 thread dump format includes structured task trees — actually an improvement |

---

## Success Metrics

| Metric | Baseline | Target (Q2 End) |
|--------|----------|-----------------|
| `ThreadLocal` usage count | 1 (`SecurityContext`) | 0 (replaced with `ScopedValue`) |
| Lombok `@Data`/`@Value` DTO count | ~30 estimated | 0 (all converted to records) |
| Non-exhaustive `switch` on sealed types | ~10 | 0 (all exhaustive, no `default` on sealed) |
| `String.formatted()` / `"" + ""` concatenation sites | ~50 | 0 (all string templates) |
| `Collectors.toList()` call sites | 12 | 0 (all `.toList()`) |
| Deprecated `FilterType` values in enum | 12 | 0 (removed) |
| Service cold start time (Docker) | ~4s | ~2.5s (CDS archives) |
| Heap footprint per service (p50) | ~350MB | ~300MB (Compact Object Headers + record migration) |

