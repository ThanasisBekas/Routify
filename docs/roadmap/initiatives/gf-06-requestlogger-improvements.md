# Initiative GF-06 — RequestLogger Performance & Configurability

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 4 (Advanced Features) · **Owner:** Gateway team  
> **Category:** Observability · **Priority:** Medium

---

## Problem Statement

1. **Body capture overhead** — when `logRequestBody=true`, the entire body is eagerly buffered into memory, which can cause OOM for large file uploads.
2. **No sampling** — every request generates a telemetry event, which under high traffic can overwhelm the `routify.request.telemetry` Kafka topic.
3. **Flat header sanitization** — `REDACTED_HEADERS` is hardcoded; operators cannot customise which headers to capture or suppress.
4. **No structured JSON log** — `log.info()` messages use positional interpolation, making log aggregation (ELK, Loki) harder.

## Solution Overview

Add body capture limits, probabilistic sampling, configurable header allow/denylists, structured JSON logging, and path exclusion patterns.

---

## Detailed Implementation Steps

### Step 1: Add `maxBodyCaptureBytes` Config Param

**Files to modify:**
- `routify-api-gateway/.../filter/observability/RequestLoggerGatewayFilterFactory.java`

**New config parameter:**
```yaml
maxBodyCaptureBytes: 4096  # default, hard upper bound: 65536 (64 KB)
```

**Implementation:**
- Read at most `maxBodyCaptureBytes` from the request body `Flux<DataBuffer>`.
- If the body exceeds the limit, truncate and append `[TRUNCATED at 4096 bytes]` marker.
- Enforce a hard upper bound of 64 KB regardless of config value.

**Task list:**
- [ ] Add `maxBodyCaptureBytes` config parameter (default 4096)
- [ ] Enforce hard upper bound of 64 KB
- [ ] Truncate body with marker text
- [ ] No behavior change when `logRequestBody=false`

---

### Step 2: Add `samplingRate` Config Param

**New config parameter:**
```yaml
samplingRate: 1.0  # default (capture all), range 0.0–1.0
```

**Implementation:**
- On each request, generate a random value via `ThreadLocalRandom.current().nextDouble()`.
- If value > `samplingRate`, skip telemetry event publishing (still log at TRACE level).
- `samplingRate=1.0` captures everything (backward compatible default).
- `samplingRate=0.0` disables telemetry publishing entirely.

**Task list:**
- [ ] Add `samplingRate` config parameter (default `1.0`)
- [ ] Implement probabilistic sampling with `ThreadLocalRandom`
- [ ] Validate range 0.0–1.0 at config bind time
- [ ] TRACE-level log for skipped events

---

### Step 3: Add `headerAllowlist` / `headerDenylist` Config Params

**New config parameters:**
```yaml
headerAllowlist: []        # empty = capture all (except deny)
headerDenylist:             # overrides allow
  - Authorization
  - Cookie
  - X-Api-Key
```

**Behavior:**
- If `headerAllowlist` is non-empty, only capture headers in the allowlist.
- `headerDenylist` always takes precedence (deny overrides allow).
- Default denylist includes the current hardcoded `REDACTED_HEADERS` set.
- Redacted headers appear as `<header-name>: [REDACTED]` in telemetry.

**Task list:**
- [ ] Add `headerAllowlist` and `headerDenylist` config parameters
- [ ] Implement deny-overrides-allow logic
- [ ] Migrate hardcoded `REDACTED_HEADERS` to default denylist
- [ ] Backward compatible when both lists are empty

---

### Step 4: Structured JSON Log Format

**Implementation:**
- Replace positional `log.info("method={} path={} status={} elapsed={}ms", ...)` with SLF4J MDC enrichment.
- Set MDC keys: `method`, `path`, `status`, `elapsedMs`, `correlationId`, `routeId`, `clientIp`.
- Log a single JSON-friendly message: `log.info("Request completed")` — the structured fields come from MDC.
- Ensure MDC is cleared after each request (already handled by `SecurityContext.clearMdc()`).

**Task list:**
- [ ] Enrich MDC with request metadata fields
- [ ] Replace positional log interpolation with MDC-based structured logging
- [ ] Ensure MDC cleanup after each request

---

### Step 5: Add `skipPaths` Config Param

**New config parameter:**
```yaml
skipPaths:
  - "/actuator/**"
  - "/health"
  - "/favicon.ico"
```

**Implementation:**
- Compile patterns to `PathPattern` instances at config bind time.
- Before processing, check if the request path matches any skip pattern.
- Matched requests bypass both logging and telemetry publishing entirely.

**Task list:**
- [ ] Add `skipPaths` config parameter (list of glob patterns)
- [ ] Compile patterns at config bind time
- [ ] Skip logging and telemetry for matched paths

---

### Step 6: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/observability/RequestLoggerConfigTest.java`

**Test cases:**
- Body capture truncated at `maxBodyCaptureBytes`
- Hard upper bound enforced (config > 64KB → capped at 64KB)
- `samplingRate=0.1` produces approximately 10% of events (statistical test)
- `samplingRate=0.0` produces zero events
- `headerAllowlist=["Content-Type"]` captures only `Content-Type`
- `headerDenylist=["Authorization"]` redacts `Authorization`
- Deny overrides allow
- `skipPaths=["/health"]` skips `/health` requests
- Default config produces identical behavior to current implementation

**Task list:**
- [ ] Write tests for all new config parameters
- [ ] Write backward compatibility test (all defaults)
- [ ] Write statistical sampling test

---

## Acceptance Criteria

- [ ] Body capture capped at `maxBodyCaptureBytes` (hard limit 64 KB)
- [ ] `samplingRate=0.1` produces ~10% of telemetry events
- [ ] `headerAllowlist` and `headerDenylist` work as documented
- [ ] `skipPaths` excludes matching requests from logging
- [ ] No behaviour change when all config is at defaults

