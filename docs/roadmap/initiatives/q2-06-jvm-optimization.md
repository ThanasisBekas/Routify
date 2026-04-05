# Initiative Q2-06 — JVM Runtime & Docker Optimization

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 6–8 · **Owner:** Platform / DevOps

---

## Problem Statement

All Dockerfiles already use `eclipse-temurin:25-jre-alpine` and `-XX:+UseZGC`, but several Java 25 runtime optimizations are not yet enabled: Compact Object Headers (reduces per-object overhead), CDS archives (faster startup), and Generational ZGC (improved throughput). Service cold-start time is ~4s — acceptable but improvable.

---

## Detailed Implementation Steps

### Step 1: Enable Generational ZGC

**Current flag:** `-XX:+UseZGC`  
**Add:** `-XX:+ZGenerational` (default in JDK 25, but set explicitly for clarity)

Generational ZGC separates young/old generations, significantly reducing pause times for short-lived objects (common in the gateway's request processing).

**Files to modify:** All 7 service Dockerfiles + `docker-compose.app.yml` `JAVA_OPTS`.

**Task list:**
- [ ] Add `-XX:+ZGenerational` to all Dockerfiles
- [ ] Verify with `java -XX:+PrintFlagsFinal -version | grep ZGenerational`

---

### Step 2: Enable Compact Object Headers

**Flag:** `-XX:+UseCompactObjectHeaders`

Reduces object header size from 12 bytes to 8 bytes (32-bit), saving ~5-10% heap on entity-heavy workloads. This is experimental in JDK 25 but stable for production use with ZGC.

**Task list:**
- [ ] Add `-XX:+UseCompactObjectHeaders` to all Dockerfiles
- [ ] Monitor heap usage before/after with Prometheus `jvm_memory_used_bytes`
- [ ] Run for 1 week in staging before production

---

### Step 3: CDS (Class Data Sharing) Archives

**Goal:** Pre-compute class metadata to reduce startup time by ~30%.

**Two-stage Dockerfile pattern:**
```dockerfile
# ─── CDS training stage ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS cds-training
WORKDIR /app
COPY --from=build /build/routify-admin-api/target/*.jar app.jar
RUN java -XX:ArchiveClassesAtExit=app-cds.jsa \
         -Dspring.context.exit=onRefresh \
         -jar app.jar || true

# ─── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S routify && adduser -S routify -G routify
USER routify
WORKDIR /app
COPY --from=build /build/routify-admin-api/target/*.jar app.jar
COPY --from=cds-training /app/app-cds.jsa app-cds.jsa
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
               -XX:+UseZGC -XX:+ZGenerational \
               -XX:+UseCompactObjectHeaders \
               -XX:SharedArchiveFile=app-cds.jsa"
```

**Task list:**
- [ ] Update Dockerfile for each service (7 services)
- [ ] Verify CDS archive is loaded: `-Xlog:class+load` shows "shared" sources
- [ ] Measure startup time before/after (target: 4s → 2.5s)
- [ ] Update `Dockerfile.ci` files similarly

---

### Step 4: Docker Compose Healthcheck Optimization

After CDS startup improvement, reduce `start_period` in `docker-compose.app.yml`:

**Before:** `start_period: 30s`  
**After:** `start_period: 15s`

**Task list:**
- [ ] Update all service healthchecks in `docker-compose.app.yml`
- [ ] Verify services are Ready within the new start period

---

### Step 5: JVM Flags Audit

Review all `JAVA_OPTS` across Dockerfiles and consolidate:

**Recommended final flag set:**
```
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+UseZGC
-XX:+ZGenerational
-XX:+UseCompactObjectHeaders
-XX:SharedArchiveFile=app-cds.jsa
-Dspring.threads.virtual.enabled=true
```

**Gateway-specific addition (reactive, no virtual threads):**
```
-Dio.netty.allocator.type=pooled
-Dio.netty.eventLoopThreads=4
```
(No `-Dspring.threads.virtual.enabled=true` for gateway)

**Task list:**
- [ ] Consolidate and standardize JAVA_OPTS across all Dockerfiles
- [ ] Document JVM flags in service READMEs
- [ ] Verify gateway does NOT enable virtual threads

---

## Acceptance Criteria

- [ ] All services use Generational ZGC + Compact Object Headers
- [ ] CDS archives generated and loaded for all services
- [ ] Cold-start time reduced from ~4s to ~2.5s (measured via Docker healthcheck timing)
- [ ] Heap footprint reduced by ~5-10% (measured via Prometheus metrics)
- [ ] `docker-compose.app.yml` healthcheck `start_period` reduced to 15s
- [ ] All services pass healthchecks and function correctly with new JVM flags

