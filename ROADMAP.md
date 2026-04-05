# Routify — Improvement Roadmap

> Phased, incremental plan for hardening, enhancing, and scaling the Routify platform.  
> Each phase is deliberately **small** — complete one before starting the next.

---

## Phase 1 — Test Foundation

**Goal:** Go from zero tests to a safety net covering the most fragile boundaries.

No `src/test/` directory exists in any module today. The CI pipeline (`ci.yml`) runs `mvn clean package -DskipTests`. This phase introduces test infrastructure and a first wave of tests targeting inter-service contracts — the highest-risk area.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 1.1 | ~~Add test dependencies to parent POM~~ ✅ | `pom.xml` | `spring-boot-starter-test`, `testcontainers` (PostgreSQL, Kafka, RabbitMQ), `spring-kafka-test`, `spring-rabbit-test` in `<dependencyManagement>`. Added `maven-surefire-plugin` + `maven-failsafe-plugin` with JDK 21 `--add-opens` args. |
| 1.2 | ~~`CommandEvent` serialisation round-trip tests~~ ✅ | `routify-common` | 36 tests: parameterised round-trip for all 29 sealed subtypes + 5 edge cases (Unknown fallback, null tenantId, empty maps, nullable fields, `@JsonSubTypes` completeness check). |
| 1.3 | ~~`QueryRequest`/`QueryResponse` round-trip tests~~ ✅ | `routify-common` | 82 tests: 38 QueryRequest subtypes + 32 QueryResponse subtypes parameterised round-trips + 10 edge cases (Unknown fallback, completeness checks, factory methods, null optionals, empty lists). |
| 1.4 | ~~`RoutifyException` → ProblemDetail mapping~~ ✅ | `routify-common` | 15 tests: parameterised for all 10 sealed subtypes (status, errorCode, type URI, title, detail, path), plus RateLimitExceeded retryAfter field, GatewayError with cause, generic 500 safe-message, completeness check. |
| 1.5 | ~~`SecurityContext` lifecycle test~~ ✅ | `routify-common` | 14 tests: thread-local lifecycle (set→current→clear, overwrite, idempotent clear), current() throws IllegalStateException when empty, cross-thread isolation (2 tests), role helpers (hasRole case-insensitive, isSuperAdmin for all 4 roles, isTenantAdmin for all 4 roles, case-insensitive role helpers), record equality/inequality, nullable fields. |
| 1.6 | ~~`KafkaDlqErrorHandlerFactory` behaviour test~~ ✅ | `routify-common` | 25 tests: exponential back-off params (initial 1s, multiplier 2.0, max 30s, interval sequence, ~5 retries), DLQ topic naming (`<topic>.DLQ` with partition preservation, 5 scenarios), non-retryable exception classification (3 Jackson deserialization types + 4 retryable controls), MDC correlationId lifecycle (set/clear/preserve, 3 tests), factory utility constraints (private ctor, final class, non-null return). |
| 1.7 | ~~Enable tests in CI~~ ✅ | `.github/workflows/ci.yml` | Rewrote CI pipeline: added `changes` job using `dorny/paths-filter@v3` to gate backend/frontend jobs on actual file changes; split backend into `backend-build` (compile-only, `-DskipTests`) and `backend-test` (`mvn clean verify` — runs unit + integration tests); added test report upload as artifact (7-day retention); frontend job now gated on `routify-dashboard/**` changes. |
| 1.8 | ~~Add Vitest to dashboard~~ ✅ | `routify-dashboard` | 29 tests across 2 files: `vitest` 4.1 + `@testing-library/react` + `@testing-library/jest-dom` + `happy-dom`. `cn()` — 8 tests (clsx merging, tailwind-merge dedup, conditionals, arrays, objects, empty, null/undefined, responsive variants). `extractApiError()` — 10 tests (RFC 9457 detail, title fallback, Axios message, network error, custom fallback, null/undefined, string data, detail-over-title preference). `apiClient` refresh-lock interceptor — 11 tests (JWT injection, X-Tenant-Id injection/preservation/absence, 401→refresh→retry, concurrent 401 queue with single refresh, infinite loop guard for refresh endpoint, logout on refresh failure, non-401 passthrough, double-retry prevention). Added `test` + `test:ci` scripts; CI `frontend` job now runs `npm run test:ci`. |

**Exit criteria:** CI runs tests on every PR; all contract serialisation tests pass.

---

## Phase 2 — Security Hardening

**Goal:** Close the open security gaps discovered in the codebase.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 2.1 | ~~Implement API Key validation~~ ✅ | `routify-api-gateway`, `routify-common` | Rewrote `ApiKeyAuthGatewayFilterFactory` with reactive Redis hash lookup (`ReactiveStringRedisTemplate.opsForHash().entries()`). Added `RedisKeys.APIKEY_PREFIX` constant (`routify:apikeys:<key>`) for shared namespace. On valid key: injects `X-Auth-User-Id`, `X-Auth-Tenant-Id`, `X-Auth-Role`, `X-Auth-Email`, `X-Auth-Type=API_KEY` headers from Redis hash fields using `RoutifyHeaders` constants (no string literals). Supports optional `expiresAt` epoch-second field for explicit expiry check alongside Redis TTL. Returns RFC 9457 ProblemDetail errors: `MISSING_API_KEY` (401), `INVALID_API_KEY` (401), `API_KEY_EXPIRED` (401), `API_KEY_VALIDATION_FAILED` (502 on Redis failure). Records `routify.auth.failures{method=API_KEY}` counter via `MeterRegistry`. Redis hash provisioning (identity-service CRUD) is a follow-up. |
| 2.2 | ~~Add method-level authorization~~ ✅ | `routify-admin-api` | Added 85 `@PreAuthorize`/`@Secured` annotations across 12 controllers. Role matrix: **VIEWER** — read-only (all GET endpoints); **OPERATOR** — read + write routes/filters/certs, replay actions, AI test; **TENANT_ADMIN** — OPERATOR + user management (CRUD), gateway config writes, gateway reload; **SUPER_ADMIN** — TENANT_ADMIN + tenant lifecycle (create/update/suspend/reactivate). Controllers covered: `AdminRoutesController`, `AdminFiltersController`, `AdminUsersController`, `AdminCertificatesController`, `AdminCertGroupsController`, `AdminAuditController` (class-level), `AdminReplayController`, `AdminAiFilterController`, `AdminAiModifierController`, `AdminDashboardController` (class-level), `AdminTenantsController` (added missing guards for suspend/reactivate + GET reads), `GatewayConfigController` (all 30+ endpoints). Uses `hasAnyRole()` SpEL with `ROLE_` prefix matching `JwtAuthFilter`'s `SimpleGrantedAuthority("ROLE_" + role)`. `@EnableMethodSecurity(securedEnabled = true)` was already active. |
| 2.3 | ~~Add nginx security headers~~ ✅ | `routify-dashboard` | Added `Content-Security-Policy` (default-src/script-src 'self', style-src 'self' 'unsafe-inline', img/font data:, connect-src ws:/wss: for WebSocket, frame-ancestors 'none'), `Strict-Transport-Security` (1 year, includeSubDomains, preload), `Permissions-Policy` (camera, microphone, geolocation, payment, usb, magnetometer, gyroscope, accelerometer all disabled). Added `gzip_min_length 256;` to skip tiny responses. Reorganised `nginx.conf` with security headers at server level before location blocks. |
| 2.4 | ~~Protect Redis with a password~~ ✅ | `docker-compose.yml` | Redis now starts with `--requirepass ${REDIS_PASS}`. Added `spring.data.redis.password: ${REDIS_PASS:}` to all 5 services that use Redis (admin-api, api-gateway, identity-service, route-service, ai-service). Added `spring.data.redis.password` to `required-secrets` in all 5 services. Updated `docker-compose.app.yml` to pass `REDIS_PASS` to all Redis-dependent containers. Updated healthcheck to authenticate with `redis-cli -a`. Added `REDIS_PASS` to `environments/README.md`. |
| 2.5 | ~~Add `ai-service` circuit breaker in admin-api~~ ✅ | `routify-admin-api` | Added `ai-service` circuit breaker instance (sliding-window 10, failure-rate 50%, wait 15s, slow-call threshold 5s/80% — matching ai-service's own CB params) and time limiter (10s hard cap — matching the ai-service's OpenAI timeout) to `application.yml` alongside the existing 4 downstream CB instances. `AiMessagingClient` already used `@CircuitBreaker(name = "ai-service")` — it was falling through to Resilience4j defaults; now it has explicit tuning. |

**Exit criteria:** No endpoint is accessible without proper role; API key validation is functional; Redis is password-protected.

---

## Phase 3 — Observability & Monitoring

**Goal:** Add distributed tracing and expand alerting beyond AI-only rules.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 3.1 | ~~Add distributed tracing~~ ✅ | All services | Added `micrometer-tracing-bridge-otel` (1.4.4) + `opentelemetry-exporter-otlp` (1.38.0) to parent POM `<dependencyManagement>` and all 7 service POMs. Added `spring-boot-starter-actuator` to the 3 services that were missing it (route-service, identity-service, audit-service). Enabled observation on all custom Kafka `ConcurrentKafkaListenerContainerFactory` beans (7 factories) and `KafkaTemplate` beans (7 templates) via `setObservationEnabled(true)`. Enabled observation on all custom `RabbitTemplate` beans (8 templates) via `setObservationEnabled(true)`. Added `management.tracing` (W3C propagation, configurable sampling via `TRACING_SAMPLING_PROBABILITY`), `management.otlp.tracing.endpoint` (configurable via `OTEL_EXPORTER_OTLP_ENDPOINT`) to all 7 service `application.yml` files. Added Grafana Tempo 2.4.1 container to `docker-compose.yml` (ports 3200/4317/4318, local WAL+block storage, 72h retention). Added `OTEL_EXPORTER_OTLP_ENDPOINT` env var to all 7 services in `docker-compose.app.yml`. Provisioned Tempo datasource in Grafana (`docker/grafana/provisioning/datasources/tempo.yml`) with trace-to-metrics correlation and service map via Prometheus UID. Added `tempo_data` volume to `docker-compose.yml` and `reset-data.sh`. |
| 3.2 | ~~Enrich MDC with `tenantId` + `userId`~~ ✅ | All services | Added `setMdc()`, `clearMdc()`, and `putMdc(userId, tenantId, correlationId)` helper methods to `SecurityContext` with centralised MDC key constants (`MDC_USER_ID`, `MDC_TENANT_ID`, `MDC_CORRELATION_ID`). `clear()` now also clears MDC. Updated 4 servlet auth filters to set MDC after authentication and clear after filter chain: `JwtAuthFilter` (admin-api), `GatewayPreAuthFilter` (route-service, ai-service), `CertVaultPreAuthFilter` (cert-vault). Updated `KafkaDlqErrorHandlerFactory` to use `SecurityContext.MDC_CORRELATION_ID` constant instead of string literal. Updated log pattern in all 7 service `application.yml` files to include `[tenant=%X{tenantId}] [user=%X{userId}]`. Identity-service and audit-service (internal-only, no JWT filter) inherit MDC from RabbitMQ/Kafka observation propagation added in 3.1. |
| 3.3 | ~~Add platform alert rules~~ ✅ | `docker/prometheus/alert_rules.yml` | Expanded from 2 AI-only groups (7 rules) to 8 groups (20 rules). New groups: **routify.platform.circuitbreakers** — admin-api CB open (any of 5 instances), CB failure rate rising (>40%); **routify.platform.database** — HikariCP pool >80% capacity, connection acquisition timeouts; **routify.platform.kafka** — consumer lag >1000 (warning) / >10,000 (critical) via `kafka_consumer_fetch_manager_records_lag_max`, DLQ records appearing on any `.DLQ` topic; **routify.platform.rabbitmq** — RabbitMQ listener failures; **routify.platform.services** — service down (`up == 0`), HTTP 5xx error rate >5%, JVM heap usage >85%; **routify.gateway** — auth failure spike (>10/sec), rate-limit spike (>50/sec). All rules use `{{ $labels.job }}` / `{{ $labels.name }}` for multi-instance awareness. Preserved all 7 existing AI filter/modifier rules unchanged. |
| 3.4 | ~~Expand `RoutifyMetrics`~~ ✅ | `routify-common`, `routify-admin-api` | Added 4 new metric families to `RoutifyMetrics`: **`routify.outbox.pending`** gauge (AtomicLong, `setOutboxPending(long)` — for outbox pollers to report backlog), **`routify.rpc.latency`** timer per exchange (`rpcTimer(exchange)` — lazy ConcurrentHashMap, tagged with `exchange`), **`routify.dlq.events`** counter per DLQ topic (`recordDlqEvent(topic)` — lazy, tagged with `topic`), **`routify.cert.expiry.days`** gauge per cert ID (`setCertExpiryDays(certId, daysLeft)` — lazy, tagged with `certId`, supports negative for expired). Added `MeterRegistry` field to support lazy metric creation. Instrumented `AmqpServiceClientSupport` with `Timer.Sample` in all 3 `rpc()` overloads that call `sendAndReceive` — timing recorded in `finally` block (covers success + failure). Added 5-arg constructor accepting optional `RoutifyMetrics` (4-arg delegates with `null` for backward compat). Wired `RoutifyMetrics` into all 8 admin-api messaging clients: `RouteServiceClient`, `RouteFilterMessagingClient`, `IdentityMessagingClient`, `AuditMessagingClient`, `CertVaultMessagingClient`, `AiMessagingClient`, `GatewayActuatorClient`, `RouteServiceConfigClient`. |
| 3.5 | ~~Add missing DLQ constant~~ ✅ | `routify-common`, `routify-audit-service` | Added `DLQ_AI_FILTER_DECISIONS = AI_FILTER_DECISIONS + ".DLQ"` to `KafkaTopics` alongside existing `DLQ_AI_MODIFICATION_EVENTS`. Registered the new topic in `DlqEventConsumer`'s `@KafkaListener` so failed AI filter decision records are persisted to `routify_audit.dlq_event`. Updated `AGENTS.md` DLQ constants list. |

**Exit criteria:** Traces flow end-to-end through Grafana Tempo; alerts fire for non-AI failures; DLQ/outbox are monitored.

---

## Phase 4 — Performance & Caching

**Goal:** Reduce latency and improve throughput across the platform.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 4.1 | ~~Add application-level caching~~ ✅ | Multiple | Added Caffeine in-process caches to 3 services. **route-service:** `gatewaySnapshot` cache (maximumSize=1, expireAfterWrite=60s) on `RouteService.findAllActiveWithFilters()` — evicted on every `OutboxPoller.pollAndPublish()` cycle. **identity-service:** `users` cache (maximumSize=500, TTL=120s) on `UserService.findAll()`/`findById()` — evicted on create/update/delete; `tenants` cache (maximumSize=100, TTL=300s) on `TenantService.findAll()`/`findById()` — evicted on create/update/suspend/reactivate. **admin-api:** `activeWorkspaces` cache (maximumSize=1, TTL=30s) on `IdentityMessagingClient.listActiveWorkspaces()` — evicted on tenant create/update/suspend/reactivate. Added `@EnableCaching` + `CacheConfig` class to each service. Caffeine dependency added to all 3 POMs (version managed by Spring Boot parent). Cache stats enabled via `recordStats()` — auto-exposed as Micrometer `cache_*` metrics in Prometheus. |
| 4.2 | ~~Tune audit-service HikariCP~~ ✅ | `routify-audit-service` | Increased `maximum-pool-size` from `10` → `15` to accommodate 3 concurrent Kafka consumer threads doing `@Transactional` batch inserts + RabbitMQ query handlers + replay service. Added `connection-timeout: 10000` (10s, explicit fail-fast instead of default 30s). Added `pool-name: audit-pool` for Prometheus `hikaricp_*` metric labelling. `minimum-idle: 3` retained. |
| 4.3 | ~~Add frontend code-splitting~~ ✅ | `routify-dashboard` | Converted all 9 protected page imports in `App.tsx` to `React.lazy()` with `<Suspense>` wrappers and a consistent `PageLoader` spinner fallback. Lazy-loaded pages: `RouteWorkflowPage`, `WorkflowBuilderPage`, `FiltersPage`, `AuditPage`, `SettingsPage`, `UsersPage`, `GatewayPage`, `CertVaultPage`, `WorkspacesPage`. Only `LoginPage` and `ChangePasswordPage` remain eagerly loaded (entry points). **Result:** initial JS bundle dropped from **1,137 KB → 428 KB** (62% reduction, 135 KB gzip). Vite's >500 KB chunk warning eliminated. Heaviest lazy chunks: `RouteWorkflowPage` (150 KB), `@xyflow/react` styles (183 KB shared), `GatewayPage` (79 KB). |
| 4.4 | ~~Optimize outbox polling~~ ✅ | `routify-route-service` | Added PostgreSQL `LISTEN/NOTIFY` trigger on `routify.outbox_event` INSERT (Flyway `V4__outbox_notify_trigger.sql`). Trigger function `routify.notify_outbox_insert()` fires `pg_notify('outbox_event_inserted', id)` after each row insert — delivery is deferred to transaction commit by PostgreSQL. New `OutboxNotifyListener` component opens a dedicated JDBC connection, issues `LISTEN outbox_event_inserted`, and calls `OutboxPoller.pollAndPublish()` immediately on notification. Uses a virtual thread with exponential-backoff reconnect (1s → 30s max). Configurable via `routify.outbox.notify.enabled` (default `true`). Fallback `@Scheduled` poll interval increased from **250ms → 5000ms** — the LISTEN/NOTIFY handles real-time wake-ups, the schedule is a safety net only. PostgreSQL JDBC driver scope changed from `runtime` → `compile` (required for `PGConnection`/`PGNotification` APIs). |
| 4.5 | ~~Document audit-service CB threshold asymmetry~~ ✅ | `routify-admin-api` | Confirmed the asymmetry is intentional: audit-service uses `failure-rate-threshold: 60` (vs 50 for others) because audit queries are non-critical read-only analytics — a degraded audit-service shouldn't trip the breaker as eagerly. Uses `wait-duration-in-open-state: 10s` (vs 15s) to recover faster since failures are transient. Added inline YAML comments to `application.yml` explaining both deviations. No code change needed. |

**Exit criteria:** Sub-100ms gateway snapshot reads; audit-service handles telemetry spikes without pool exhaustion; dashboard first-paint improves measurably.

---

## Phase 5 — Frontend Quality

**Goal:** Improve accessibility, design-system consistency, and developer ergonomics in the dashboard.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 5.1 | ~~Add shared UI primitives~~ ✅ | `routify-dashboard` | Created 6 reusable components in `src/components/ui/` with barrel export (`index.ts`). **Button** — 4 variants (`primary`/`secondary`/`danger`/`outline`), 3 sizes (`sm`/`md`/`lg`), `loading` spinner, `icon` slot, `forwardRef`. **Input** + **Textarea** — dark-theme styled, `mono` prop for code inputs, `forwardRef` for React Hook Form. **Modal** — composable `Modal`/`ModalHeader`/`ModalBody`/`ModalFooter` sub-components, backdrop blur, close-on-backdrop, icon with color presets, fade-in animation. **Badge** — 9 color presets (`indigo`/`emerald`/`amber`/`red`/`sky`/`purple`/`gray`/`rose`/`yellow`), 2 sizes (`xs`/`sm`), optional icon slot. **Card** — dark-glass container with optional padding. **Table** — composable namespace (`Table.Root`/`Header`/`Body`/`Row`/`Head`/`Cell`), clickable rows, consistent column styling. All components use `cn()` for class merging and accept `className` overrides. Existing `Select` re-exported from barrel. |
| 5.2 | ~~Add ARIA labels and accessibility~~ ✅ | `routify-dashboard` | Installed `eslint-plugin-jsx-a11y` and added it to `eslint.config.js` with recommended rules. Initial scan found **87 violations** across 4 rule types in 21 files. **Fixed all 34 interactive-element errors** (`click-events-have-key-events` + `no-static-element-interactions` + `no-noninteractive-element-interactions`): converted 5 clickable `<div>`s to `role="button"` with `tabIndex={0}` + `onKeyDown` (Enter/Space) in `CertGroupsPage`, `RouteWorkflowCard`, `RouteTriggerNode`, `SharedNodes` (UpstreamNode + FilterNode); converted 1 toggle `<div>` to `<button role="switch" aria-checked>` in `RouteCurlModal`; added `role="dialog" aria-modal="true"` to 3 modal panels (`Modal`, `AuditDetailModal`, `RouteCurlModal`); added `aria-label` to 2 modals. Suppressed 7 backdrop `stopPropagation` patterns (standard modal UX). **Rule tuning:** `no-autofocus` → warn (intentional modal UX, 12 occurrences); `label-has-associated-control` → warn with `depth: 3` (36 false positives from `FormField`/`Field` wrapper components). ARIA attributes increased from **5 → 24** across the dashboard. Zero `jsx-a11y` errors remain. |
| 5.3 | ~~Add per-page document titles~~ ✅ | `routify-dashboard` | Created `useDocumentTitle(title)` hook in `src/hooks/useDocumentTitle.ts` — sets `document.title` to `"<title> — Routify"` on mount/update and restores base title on unmount. Added to all 11 page components: `RouteWorkflowPage` ("Routes"), `WorkflowBuilderPage` ("Route Builder"), `FiltersPage` ("Filters"), `AuditPage` ("Audit Log"), `SettingsPage` ("Settings"), `UsersPage` ("Users"), `GatewayPage` ("Gateway"), `CertVaultPage` ("Certificate Vault"), `WorkspacesPage` ("Workspaces"), `LoginPage` ("Login"), `ChangePasswordPage` ("Change Password"). Added 6 Vitest tests covering title set, undefined/empty fallback, unmount restore, re-render update, and all 11 page titles. |
| 5.4 | ~~Add React `ErrorBoundary` per module~~ ✅ | `routify-dashboard` | Enhanced `ErrorBoundary` component with: `retryCount` state that increments on retry and is used as a React `key` to force full child remount; render-prop fallback support (`({ error, retry }) => ReactNode`); optional `label` prop for page context; `||` instead of `??` for empty error messages. Wrapped all 9 lazy-loaded module routes in `App.tsx` with individual `<ErrorBoundary label="...">` boundaries — a crash in one page (e.g. `WorkflowBuilderPage`) no longer takes down the entire app; other pages remain navigable. Labels: Routes, Route Builder, Filters, Gateway, Certificate Vault, Audit Log, Users, Settings, Workspaces. Added 16 Vitest tests covering: child rendering (2), default fallback (4), label prop (2), retry/reset with remount (3), custom static + render-prop fallback (2), console.error logging (1), boundary isolation (2). |
| 5.5 | ~~Add `prettier` for consistent formatting~~ ✅ | `routify-dashboard` | Installed `prettier` (3.x) + `eslint-config-prettier` as devDependencies. Created `.prettierrc` matching existing code style: `semi: false`, `singleQuote: true`, `trailingComma: "all"`, `tabWidth: 2`, `printWidth: 120`, `endOfLine: "lf"`. Created `.prettierignore` (dist, mockServiceWorker.js, node_modules, *.svg). Added `eslint-config-prettier` as last extends in `eslint.config.js` to disable all ESLint formatting rules that conflict with Prettier. Updated `lint` script to `eslint . && prettier --check .` — both tools run in CI. Added `format` script (`prettier --write .`) for developer convenience. Auto-formatted all 121 files that had style differences. Fixed pre-existing unused `act` import in `useDocumentTitle.test.ts`. Excluded `src/__tests__/` from `tsconfig.app.json` to fix pre-existing build failure (vitest globals not available in build tsconfig). Build, lint, and all 51 tests pass. |

**Exit criteria:** Zero `eslint-plugin-jsx-a11y` violations; all forms use shared UI primitives; every page has a unique document title.

---

## Phase 6 — Infrastructure & Docker

**Goal:** Optimize container builds, fix networking, and harden the containerised stack.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 6.1 | Fix `docker-compose.yml` network isolation | `docker-compose.yml` | Infrastructure services (postgres, redis, kafka, rabbitmq) use the default bridge network, but app services in `docker-compose.app.yml` use the `routify` network. App containers **cannot reach** infrastructure containers. Add `networks: [routify]` to all infra services, or declare a shared external network. |
| 6.2 | Remove deprecated `version` key | `docker-compose.yml` | `version: '3.8'` is deprecated in Docker Compose V2. Remove it. |
| 6.3 | Pin observability image versions | `docker-compose.yml` | `prom/prometheus:latest` and `grafana/grafana:latest` risk breaking changes on pull. Pin to specific versions (e.g. `prom/prometheus:v2.53.0`, `grafana/grafana:11.1.0`). |
| 6.4 | Add `npm ci` cache mount to dashboard Dockerfile | `routify-dashboard` | `Dockerfile` runs `npm ci` without `--mount=type=cache`. Add `RUN --mount=type=cache,target=/root/.npm npm ci` for faster rebuilds. |
| 6.5 | Provide lean Dockerfile variant for CI | All services | Current Dockerfiles run a full Maven build inside Docker (copy `.mvn/`, `mvnw`, run `dependency:go-offline`). This is self-contained but slow. Add a `Dockerfile.ci` that does `COPY target/*.jar app.jar` — used after `mvn package` in CI. Keep the existing Dockerfile for standalone `docker compose build`. |
| 6.6 | Add actuator port to Dockerfile HEALTHCHECK | All services | Dockerfiles use the app port for health checks (`wget -qO- http://localhost:808X/actuator/health`) but actuator is on port `908X`. Update HEALTHCHECK to use the correct actuator port. |

**Exit criteria:** `docker compose -f docker-compose.yml -f docker-compose.app.yml up` works end-to-end; image builds are under 60s in CI; all health checks hit the correct port.

---

## Phase 7 — Resilience & Error Handling

**Goal:** Harden messaging paths and graceful degradation.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 7.1 | Add idempotency checks in command consumers | `routify-route-service`, `routify-identity-service`, `routify-cert-vault` | `CommandEvent.commandId()` is documented as the idempotency key, but verify that consumers actually check for duplicate `commandId` before applying mutations. Without this, the Kafka at-least-once guarantee can cause duplicate side effects. |
| 7.2 | Add retry logic for RabbitMQ `null` replies | `routify-common` | `AmqpServiceClientSupport.rpc()` throws `RoutifyException.GatewayError` on `null` reply (broker timeout). Consider a single transparent retry before surfacing the error — transient network blips currently fail immediately. |
| 7.3 | Add graceful shutdown for outbox pollers | `routify-route-service` | Verify `OutboxPoller` honours `@PreDestroy` / Spring lifecycle to flush in-progress batches before JVM shutdown. A hard kill mid-batch can leave outbox records in `IN_PROGRESS` state permanently. |
| 7.4 | Add Kafka consumer lag monitoring | All consumers | No `kafka_consumer_lag` metric is exported. Add `spring-kafka` `ConsumerAwareRebalanceListener` or Micrometer's Kafka binder metrics to track consumer lag per group/topic. |

**Exit criteria:** Duplicate Kafka commands are safely rejected; outbox drains cleanly on shutdown; consumer lag is visible in Grafana.

---

## Phase 8 — Test Coverage Expansion

**Goal:** Build on Phase 1's contract tests with service-layer integration tests.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 8.1 | Route-service integration tests | `routify-route-service` | Testcontainers PostgreSQL + embedded Kafka. Test full flow: receive `CreateRoute` command → persist → outbox write → `OutboxPoller` publishes to Kafka → verify event on topic. |
| 8.2 | Identity-service auth flow tests | `routify-identity-service` | Test login → JWT issuance → refresh → logout → blocklist via embedded Redis + RabbitMQ. |
| 8.3 | Gateway filter chain tests | `routify-api-gateway` | `WebTestClient` tests for each filter factory. Priority: `JwtAuthGatewayFilterFactory` (token validation + expiry + blocklist), `FixedWindowRateLimitGatewayFilterFactory` (Redis counter), `AiGatewayFilterFactory` (mock RabbitMQ reply). |
| 8.4 | Admin-api end-to-end tests | `routify-admin-api` | Test the BFF aggregation: HTTP request → Kafka command published → mock service replies via RabbitMQ → HTTP response. Use `@EmbeddedKafka` + embedded RabbitMQ. |
| 8.5 | Dashboard E2E tests | `routify-dashboard` | Add Playwright. Test critical path: login → create route → activate → verify in list → delete. Run against mock mode (`VITE_MOCK=true`). |

**Exit criteria:** Each service has ≥1 integration test covering its primary flow; dashboard has ≥3 E2E scenarios.

---

## Phase 9 — Developer Experience

**Goal:** Speed up the inner development loop and improve onboarding.

| # | Task | Module | Detail |
|---|------|--------|--------|
| 9.1 | Add `spring-boot-devtools` for hot-reload | All blocking services | Currently any Java change requires a full restart. Add `spring-boot-devtools` (with `optional` scope) for automatic restart on class change. Exclude from `routify-api-gateway` (reactive). |
| 9.2 | Add `mvn verify` profile for quick checks | `pom.xml` | Create a `quick` Maven profile that runs only unit tests (skip integration), so `mvn verify -Pquick` takes <30s. |
| 9.3 | Add `npm run typecheck` script | `routify-dashboard` | `package.json` has `build` (which runs `tsc -b`) but no standalone typecheck command. Add `"typecheck": "tsc --noEmit"` for fast type validation without building. |
| 9.4 | Standardise `.env.example` at project root | Project root | No `.env.example` exists at the root (referenced in `SecretValidator` javadoc and `docker-compose.yml` comments). Create one listing all required variables with placeholder values. |

**Exit criteria:** Java changes hot-reload in <3s; type-checking is a standalone command; new developers can start with `cp .env.example .env && docker compose up`.

---

## Summary Matrix

| Phase | Focus | Risk Addressed | Effort |
|-------|-------|----------------|--------|
| **1** | Test Foundation | Zero test coverage | Medium |
| **2** | Security Hardening | Open TODO, missing authz, no Redis password | Medium |
| **3** | Observability | No distributed tracing, AI-only alerts | Medium |
| **4** | Performance | No caching, pool exhaustion, large bundle | Small |
| **5** | Frontend Quality | 5 ARIA attrs, 1 UI primitive, no page titles | Small |
| **6** | Infrastructure | Network isolation, wrong health ports, slow builds | Small |
| **7** | Resilience | No idempotency check, no retry, no lag monitoring | Medium |
| **8** | Test Expansion | Only contract tests from Phase 1 | Large |
| **9** | Developer Experience | Slow dev loop, missing `.env.example` | Small |

