# Initiative Q2-08 — 2.0.0 GA Release Hardening

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 10–12 · **Owner:** All teams

---

## Problem Statement

After 7 initiatives of Java 25 modernization, the codebase needs a final validation pass before cutting the `2.0.0` release. Documentation must reflect the new patterns, version numbers must be updated, and release artifacts (Docker images, changelog) must be produced.

---

## Detailed Implementation Steps

### Step 1: Full Test Suite Execution

```bash
# Java unit + integration tests (all modules)
mvn clean verify -DskipITs=false

# Frontend unit tests
cd routify-dashboard && npm run test:ci

# Frontend E2E tests
npm run test:e2e:ci
```

**Task list:**
- [ ] Run full Java test suite — fix any failures from Java 25 changes
- [ ] Run frontend test suite — fix any API contract changes
- [ ] Run E2E tests — verify dashboard flows work end-to-end
- [ ] Run full stack locally via `docker compose` and smoke-test manually

---

### Step 2: `AGENTS.md` Update

The `AGENTS.md` in the project root is the source of truth for AI coding agents. Update it to reflect Java 25 patterns.

**Sections to update:**

**Java services → add:**
- Java 25 with `ScopedValue` for `SecurityContext` (not `ThreadLocal`)
- `StructuredTaskScope` for parallel fan-out queries in admin-api and ai-service
- String templates used for all exception messages and log concatenation
- Record patterns and unnamed variables `_` in sealed-type switch expressions
- Stream Gatherers for custom intermediate operations (e.g., time-window bucketing)
- All `switch` expressions on sealed types are exhaustive (no `default` branch)

**`SecurityContext` section → update:**
```
- `security.io.routify.common.SecurityContext` — Java 25 **record** stored in a `ScopedValue` (not `ThreadLocal`).
  Use `SecurityContext.SCOPE.get()` to read. Use `ScopedValue.runWhere(SecurityContext.SCOPE, ctx, task)` to bind.
  Child virtual threads in `StructuredTaskScope` automatically inherit the scoped value.
```

**JVM/Docker section → add:**
```
- Dockerfiles use CDS archives (`-XX:SharedArchiveFile=app-cds.jsa`) for ~30% faster startup.
- Compact Object Headers enabled (`-XX:+UseCompactObjectHeaders`) for ~8% heap reduction.
- Generational ZGC (`-XX:+ZGenerational`) for improved throughput.
```

**`FilterType` section → update:**
- Remove the 12 deprecated values from the documented list
- Note: "Deprecated types were removed in 2.0.0 — DB rows with old values are migrated to `null` + `enabled=false`"

**Task list:**
- [ ] Update `AGENTS.md` with all Java 25 pattern changes
- [ ] Update `SecurityContext` documentation
- [ ] Update JVM/Docker flags documentation
- [ ] Update `FilterType` documentation
- [ ] Review complete `AGENTS.md` for accuracy

---

### Step 3: Service README Updates

Each service's `README.md` should reflect:
- Java 25 requirement
- Updated JVM flags
- CDS archive note
- Any service-specific Java 25 usage (e.g., `StructuredTaskScope` in admin-api)

**Task list:**
- [ ] Update `routify-common/README.md`
- [ ] Update `routify-admin-api/README.md`
- [ ] Update `routify-api-gateway/README.md`
- [ ] Update all other service READMEs
- [ ] Update root `README.md`

---

### Step 4: Version Bump

**Parent POM:**
```xml
<version>2.0.0</version>  <!-- was 2.0.0-SNAPSHOT -->
```

**Dashboard:**
```json
"version": "2.0.0"  // was 2.0.0-SNAPSHOT
```

**Task list:**
- [ ] Bump parent POM version to `2.0.0`
- [ ] Bump `routify-dashboard/package.json` version to `2.0.0`
- [ ] Verify all child POMs inherit the correct version
- [ ] Verify Maven build succeeds with new version

---

### Step 5: Docker Image Build & Tag

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.app.yml build
# Tag images:
docker tag routify-admin-api:latest routify-admin-api:2.0.0
docker tag routify-api-gateway:latest routify-api-gateway:2.0.0
# ... repeat for all services
```

**Task list:**
- [ ] Build all Docker images
- [ ] Tag with `2.0.0`
- [ ] Verify all images start and pass healthchecks
- [ ] Push to container registry (if applicable)

---

### Step 6: Release Notes & Changelog

**File to create:** `CHANGELOG.md` (or update existing)

**2.0.0 Release highlights:**
- **Java 25** — structured concurrency, scoped values, string templates, record patterns, stream gatherers, unnamed variables
- **ScopedValue SecurityContext** — `ThreadLocal` replaced with `ScopedValue` for virtual-thread safety
- **StructuredTaskScope** — parallel fan-out queries in admin-api (4x faster dashboard stats)
- **CDS Archives** — ~30% faster cold startup across all services
- **Compact Object Headers** — ~8% heap reduction
- **Generational ZGC** — improved GC throughput for gateway request processing
- **Deprecated FilterType removal** — 12 legacy filter types removed (DB migration included)
- **Record DTOs** — all DTOs converted from Lombok `@Data`/`@Value` to Java records
- **Exhaustive switches** — all sealed-type switches are compile-time exhaustive

**Breaking changes:**
- `SecurityContext.set()` / `SecurityContext.clear()` removed — use `ScopedValue.runWhere()` instead
- 12 deprecated `FilterType` values removed — DB migration converts existing rows to `null`/`disabled`
- Minimum Java version: 25 (was 21)

**Task list:**
- [ ] Write release notes
- [ ] Create Git tag `v2.0.0`
- [ ] Create GitHub release (if applicable)

---

## Acceptance Criteria

- [ ] All Java tests pass (unit + IT)
- [ ] All frontend tests pass (unit + E2E)
- [ ] `AGENTS.md` accurately reflects Java 25 patterns
- [ ] Version is `2.0.0` across POM and package.json
- [ ] Docker images tagged `2.0.0` pass healthchecks
- [ ] Release notes document all breaking changes
- [ ] Git tag `v2.0.0` created

