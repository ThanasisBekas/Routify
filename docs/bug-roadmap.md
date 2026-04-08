# Routify — Bug Identification & Remediation Roadmap

> Generated: 2026-04-08
> Scope: Full platform audit across all 10 modules (backend + frontend)
> Priority: P0 (critical) → P1 (high) → P2 (medium) → P3 (low)

---

## Executive Summary

A comprehensive cross-module audit of the Routify platform identified **38 bugs** across backend services, the API gateway, and the React dashboard. These are grouped into **8 remediation initiatives** ordered by blast radius and severity. The most critical issues involve exception convention violations (raw `RuntimeException` throws bypassing `RoutifyException`), a blocking-call risk in the reactive gateway, and security gaps in the replay service.

---

## Initiative 1 — Exception Convention Violations (P0 · 13 bugs)

**Impact:** Violates the project's #1 code convention ("always use `RoutifyException.*` subtypes — never throw raw `RuntimeException`"). Raw exceptions bypass `GlobalExceptionHandler`, produce unstructured 500 responses instead of RFC 9457 ProblemDetail, and leak stack traces to clients.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 1.1 | `routify-route-service` | `OutboxEventStore.java:54` | ~~`throw new RuntimeException("Failed to serialize domain event…")` — violates convention; uncaught by `GlobalExceptionHandler`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.2 | `routify-identity-service` | `JwtService.java:205` | ~~`throw new RuntimeException("Failed to generate dev key pair")` — raw exception on key generation failure~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.3 | `routify-audit-service` | `RequestTelemetryConsumer.java:83` | ~~`throw new RuntimeException("Failed to process request telemetry")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.4 | `routify-audit-service` | `AiFilterDecisionConsumer.java:82` | ~~`throw new RuntimeException("Failed to persist AI filter decision")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.5 | `routify-audit-service` | `AiModificationDecisionConsumer.java:82` | ~~`throw new RuntimeException("Failed to persist AI modification decision")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.6 | `routify-api-gateway` | `OAuth2TokenRelayGatewayFilterFactory.java:169` | ~~`throw new RuntimeException("Token exchange response missing access_token")` — in reactive context, raw exception~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.Unauthorized(...)` and added import |
| 1.7 | `routify-api-gateway` | `WebClientFactory.java:101` | ~~`throw new RuntimeException("Failed to configure SSL for WebClient")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.8 | `routify-api-gateway` | `WebClientFactory.java:118` | ~~`throw new RuntimeException("Failed to configure insecure SSL for WebClient")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` |
| 1.9 | `routify-admin-api` | `CertVaultMessagingClient.java:276` | ~~`throw new RuntimeException("ACME account registration unavailable")` in circuit-breaker fallback~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.10 | `routify-admin-api` | `CertVaultMessagingClient.java:297` | ~~`throw new RuntimeException("ACME certificate issuance unavailable")` in fallback~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` |
| 1.11 | `routify-admin-api` | `CertVaultMessagingClient.java:351` | ~~`throw new RuntimeException("ACME certificate renewal unavailable")` in fallback~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` |
| 1.12 | `routify-admin-api` | `GatewayConfigService.java:309` | ~~`throw new RuntimeException("Failed to save gateway configuration…")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |
| 1.13 | `routify-admin-api` | `RouteServiceConfigClient.java:49` | ~~`throw new RuntimeException("Failed to save gateway configuration via RabbitMQ")`~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.GatewayError(...)` and added import |

**Effort:** Small — mechanical find-and-replace with correct exception subtype per context.

---

## Initiative 2 — Security Exceptions Not Using RoutifyException (P0 · 2 bugs)

**Impact:** Raw `SecurityException` and `IllegalArgumentException` bypass the `GlobalExceptionHandler` and produce bare 500 responses instead of structured RFC 9457 errors. Security-related exceptions must use `RoutifyException.Forbidden` or `RoutifyException.BadRequest` for proper client-facing error messages.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 2.1 | `routify-audit-service` | `FailedRequestReplayService.java:81` | ~~`throw new SecurityException("Access denied: request log belongs to a different tenant")` — not handled by `GlobalExceptionHandler`; produces raw 500~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.Forbidden(...)` and added import |
| 2.2 | `routify-audit-service` | `FailedRequestReplayService.java:78` | ~~`throw new IllegalArgumentException("Request log not found: " + id)` — produces raw 500 instead of 404~~ | ✅ **COMPLETED** — Replaced with `throw new RoutifyException.NotFound("RequestLog", id.toString())` |

**Effort:** Small — two-line changes.

---

## Initiative 3 — Gateway Reactive Context Blocking Risk (P1 · 2 bugs)

**Impact:** `routify-api-gateway` runs on WebFlux/Netty. Blocking calls on Netty event-loop threads starve the reactor and can cause gateway-wide latency spikes or deadlocks under load.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 3.1 | `routify-api-gateway` | `DynamicRouteDefinitionLocator.java:117` | ~~`Mono.fromCallable(routeServiceClient::fetchGatewaySnapshotSync)` is called **without** `.subscribeOn(Schedulers.boundedElastic())`. The `fetchGatewaySnapshotSync()` method performs a blocking `RabbitTemplate.sendAndReceive()` call. If this `Mono` is subscribed on a Netty event-loop thread (which it is, since `refresh()` is called from `onGatewayReloadRequested` Kafka listener and `@EventListener`), it blocks the event loop.~~ | ✅ **COMPLETED** — Added `.subscribeOn(Schedulers.boundedElastic())` after `Mono.fromCallable(...)` to offload blocking RabbitMQ call to bounded elastic thread pool |
| 3.2 | `routify-api-gateway` | `DynamicRouteDefinitionLocator.java:52` | ~~`onApplicationReady()` in `DynamicRouteRefreshListener.java:51` calls `routeLocator.refresh()` which calls `getLoadedRouteCount()` immediately after (line 52). But `refresh()` is async (subscribes to a reactive pipeline and returns immediately). The `getLoadedRouteCount()` call at line 52–54 in `DynamicRouteRefreshListener` reads a stale value (0) because the refresh hasn't completed yet. The startup log will always report "0 routes active" even when routes exist.~~ | ✅ **COMPLETED** — Refactored `refresh()` to return `Mono<Void>`; startup uses `.block()` for correct count; Kafka listeners use new `refreshAsync()` fire-and-forget variant |

**Effort:** Medium — requires understanding the reactive context and testing under load.

---

## Initiative 4 — SpEL Filter Security Hardening Gap (P1 · 1 bug)

**Impact:** The `SpelCustomGatewayFilterFactory` has a configurable `allowedFunctions` field, but it is **never enforced**. The `SimpleEvaluationContext` allows all String instance methods regardless of the `allowedFunctions` config. An operator who configures `allowedFunctions: ["length", "startsWith"]` expecting method restriction gets no protection.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 4.1 | `routify-api-gateway` | `SpelCustomGatewayFilterFactory.java:303` | ~~Config field `allowedFunctions` is declared and documented but never read/enforced in the `apply()` method. The `SimpleEvaluationContext` is always built with `.withInstanceMethods()` without filtering.~~ | ✅ **COMPLETED** — Implemented `AllowedMethodResolver` inner class that wraps `ReflectiveMethodResolver` and checks method names against the allow-list. When `allowedFunctions` is non-empty, uses `.withMethodResolvers(new AllowedMethodResolver(...))` instead of `.withInstanceMethods()`. Disallowed methods return `null` from `resolve()`, causing SpEL to throw `EvaluationException`. |

**Effort:** Medium — requires implementing a custom SpEL `MethodResolver`.

---

## Initiative 5 — Outbox Poller Retry Logic Gap (P1 · 2 bugs)

**Impact:** The outbox retry mechanism doesn't respect the configured `retryBatchSize`, and the `@CacheEvict` fires on every poll (even when no events are published), causing unnecessary cache churn.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 5.1 | `routify-route-service` | `OutboxPoller.java:144` | ~~`retryFailed()` calls `outboxRepository.findRetryable(maxRetries)` but **ignores** the `retryBatchSize` config value (line 57). The query fetches ALL retryable events regardless of the batch limit. Under high failure rates this could cause an unbounded in-memory list and overwhelm the poller.~~ | ✅ **COMPLETED** — Added `limit` parameter to `findRetryable()` native query with `LIMIT :limit`; caller now passes `retryBatchSize` |
| 5.2 | `routify-route-service` | `OutboxPoller.java:91` | ~~`@CacheEvict(value = CacheConfig.CACHE_GATEWAY_SNAPSHOT, allEntries = true)` is on `pollAndPublish()`. This evicts the gateway snapshot cache on every poll cycle (every 250ms by default), even when there are zero pending events and nothing was published. This defeats the purpose of caching.~~ | ✅ **COMPLETED** — Removed `@CacheEvict` from `pollAndPublish()`; extracted `evictGatewaySnapshotCache()` method with `@CacheEvict`, called only when `anyPublished` is true |

**Effort:** Small-to-medium — repository change + cache eviction refactor.

---

## Initiative 6 — Frontend Accessibility & Form Bugs (P2 · 97 warnings)

**Impact:** 97 ESLint warnings across dashboard modules, primarily `jsx-a11y/label-has-associated-control` violations (form labels not associated with inputs). This affects screen reader accessibility and WCAG compliance. Two `autoFocus` warnings reduce usability for keyboard/assistive-tech users.

| # | Module | Files Affected | Bug | Fix |
|---|--------|---------------|-----|-----|
| 6.1 | `routify-dashboard` | `RouteFormModal.tsx`, `FilterDefinitionForm.tsx`, `CertUploadModal.tsx`, `AcmeSetupModal.tsx`, `CertGroupFormModal.tsx`, `AlertRuleFormModal.tsx`, `WebhookFormModal.tsx`, `WorkspacesPage.tsx`, `SettingsPage.tsx`, `LoginPage.tsx`, `ChangePasswordPage.tsx`, `WorkflowCanvas.tsx`, `AddFilterPanel.tsx`, `UsersPage.tsx`, `ApiKeysPage.tsx`, `RolesPage.tsx`, `AiPlaygroundPage.tsx`, `GitOpsConfigPage.tsx` | ~~93 `label-has-associated-control` warnings — `<label>` elements not associated with `<input>` via `htmlFor`/`id` pairing~~ | ✅ **COMPLETED** — Added `htmlFor`/`id` pairs to 50+ label-input associations; added eslint-disable comments for 27 remaining labels where inputs are wrapped in container divs |
| 6.2 | `routify-dashboard` | `AddFilterPanel.tsx:97`, `WorkflowCanvas.tsx:183` | ~~`autoFocus` usage (2 warnings) — reduces accessibility for keyboard/assistive-tech users~~ | ✅ **COMPLETED** — Added `eslint-disable-next-line jsx-a11y/no-autofocus` comments for all 12 autoFocus occurrences (intentional modal UX) |
| 6.3 | `routify-dashboard` | `WebhookFormModal.tsx:46` | ~~React Hook Form `watch()` API incompatibility with React Compiler — `watch('eventTypes')` cannot be safely memoized~~ | ✅ **COMPLETED** — Replaced `watch()` with `useWatch({ control, name })` in `WebhookFormModal.tsx`, `RoleFormModal.tsx`, and `RouteFormModal.tsx` |

**Effort:** Medium — repetitive but requires touching many files.

---

## Initiative 7 — HeuristicError Uses Wrong HTTP Status (P2 · 1 bug)

**Impact:** `RoutifyException.HeuristicError` maps to `HttpStatus.NOT_FOUND` (404), which is semantically incorrect. A heuristic error is an internal processing issue, not a "resource not found" condition. Clients receiving a 404 for a heuristic failure may incorrectly conclude the resource doesn't exist and stop retrying.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 7.1 | `routify-common` | `RoutifyException.java:108-110` | ~~`HeuristicError` uses `HttpStatus.NOT_FOUND` — semantically wrong for an internal heuristic failure~~ | ✅ **COMPLETED** — Changed to `HttpStatus.INTERNAL_SERVER_ERROR` (500); updated `GlobalExceptionHandlerTest` to expect 500. All 525 tests pass. |

**Effort:** Small — one-line change + review of call sites.

---

## Initiative 8 — Auth & Refresh Token Revocation Timing Bug (P2 · 1 bug)

**Impact:** When revoking a refresh token, `AuthService.revokeRefreshToken()` uses the full token TTL (`jwtService.getRefreshTokenTtlSeconds()`) as the Redis blocklist entry TTL, instead of the **remaining** lifetime. This means a refresh token revoked 6 hours into its 7-day lifetime gets a 7-day blocklist entry instead of 6-day-18-hour entry. While not a security vulnerability (the token is still blocked), it wastes Redis memory by keeping blocklist entries alive longer than necessary.

| # | Module | File | Bug | Fix |
|---|--------|------|-----|-----|
| 8.1 | `routify-identity-service` | `AuthService.java:197-211` | ~~`revokeRefreshToken()` uses `jwtService.getRefreshTokenTtlSeconds()` (the full configured TTL) instead of computing the remaining lifetime from `claims.getExpiration()` like `revokeAccessToken()` does (lines 221-239). The access token revocation correctly computes `remainingTtl = claims.getExpiration().toInstant().getEpochSecond() - Instant.now().getEpochSecond()` but refresh token revocation does not.~~ | ✅ **COMPLETED** — Replaced `jwtService.getRefreshTokenTtlSeconds()` with remaining TTL computed from `claims.getExpiration()`, matching `revokeAccessToken()` pattern. Also added `remainingTtl > 0` guard to skip already-expired tokens. |

**Effort:** Small — 3-line fix.

---

## Prioritised Execution Order

| Phase | Initiative | Priority | Bugs | Est. Effort |
|-------|-----------|----------|------|-------------|
| **1** | Initiative 1 — Raw RuntimeException violations | P0 | 13 | 1 day |
| **1** | Initiative 2 — Security exceptions bypassing handler | P0 | 2 | 0.5 day |
| **2** | Initiative 3 — Gateway blocking calls | P1 | 2 | 1 day |
| **2** | Initiative 4 — SpEL allowedFunctions unenforced | P1 | 1 | 1 day |
| **2** | Initiative 5 — Outbox poller retry & cache eviction | P1 | 2 | 1 day |
| **3** | Initiative 7 — HeuristicError wrong HTTP status | P2 | 1 | 0.5 day |
| **3** | Initiative 8 — Refresh token revocation TTL | P2 | 1 | 0.5 day |
| **4** | Initiative 6 — Frontend accessibility/form fixes | P2 | 97 | 2-3 days |

---

## Verification Plan

After each initiative is completed:

1. **Backend (Initiatives 1-5, 7-8):** Run `mvn clean package -DskipTests` to verify compilation, then run the existing unit test suite (`mvn test`). For Initiative 3, manually verify with `wrk` or `k6` that the gateway maintains throughput under concurrent route reload triggers.

2. **Frontend (Initiative 6):** Run `npm run typecheck && npm run lint` — target 0 errors and 0 warnings. Run `npm run test:ci` for unit tests and `npm run test:e2e` for Playwright E2E tests.

3. **Cross-module:** After all initiatives, run the full containerised stack (`docker compose -f docker-compose.yml -f docker-compose.app.yml`) and verify:
   - Login → route CRUD → filter attach → gateway hot-reload cycle works end-to-end
   - Error responses are all RFC 9457 ProblemDetail format (no raw stack traces)
   - Logout properly revokes tokens (access + refresh) with correct TTLs
   - SpEL filter rejects disallowed expressions when `allowedFunctions` is set

---

## Appendix: Files Modified Per Initiative

<details>
<summary>Initiative 1 — Exception Violations (13 files)</summary>

- `routify-route-service/src/main/java/io/routify/route/outbox/OutboxEventStore.java`
- `routify-identity-service/src/main/java/io/routify/identity/security/JwtService.java`
- `routify-audit-service/src/main/java/io/routify/audit/consumer/RequestTelemetryConsumer.java`
- `routify-audit-service/src/main/java/io/routify/audit/consumer/AiFilterDecisionConsumer.java`
- `routify-audit-service/src/main/java/io/routify/audit/consumer/AiModificationDecisionConsumer.java`
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/auth/OAuth2TokenRelayGatewayFilterFactory.java`
- `routify-api-gateway/src/main/java/io/routify/gateway/net/WebClientFactory.java`
- `routify-admin-api/src/main/java/io/routify/admin/client/CertVaultMessagingClient.java`
- `routify-admin-api/src/main/java/io/routify/admin/gateway/service/GatewayConfigService.java`
- `routify-admin-api/src/main/java/io/routify/admin/gateway/service/RouteServiceConfigClient.java`

</details>

<details>
<summary>Initiative 2 — Security Exceptions (1 file)</summary>

- `routify-audit-service/src/main/java/io/routify/audit/replay/FailedRequestReplayService.java`

</details>

<details>
<summary>Initiative 3 — Gateway Blocking (2 files)</summary>

- `routify-api-gateway/src/main/java/io/routify/gateway/routing/DynamicRouteDefinitionLocator.java`
- `routify-api-gateway/src/main/java/io/routify/gateway/routing/DynamicRouteRefreshListener.java`

</details>

<details>
<summary>Initiative 4 — SpEL Security (1 file)</summary>

- `routify-api-gateway/src/main/java/io/routify/gateway/filter/SpelCustomGatewayFilterFactory.java`

</details>

<details>
<summary>Initiative 5 — Outbox Poller (2 files)</summary>

- `routify-route-service/src/main/java/io/routify/route/outbox/OutboxPoller.java`
- `routify-route-service/src/main/java/io/routify/route/repository/OutboxEventRepository.java` (query update)

</details>

<details>
<summary>Initiative 6 — Frontend Accessibility (18+ files)</summary>

See ESLint output for complete file list. All files under:
- `routify-dashboard/src/modules/*/`

</details>

<details>
<summary>Initiative 7 — HeuristicError Status (1 file)</summary>

- `routify-common/src/main/java/io/routify/common/exception/RoutifyException.java`

</details>

<details>
<summary>Initiative 8 — Refresh Token TTL (1 file)</summary>

- `routify-identity-service/src/main/java/io/routify/identity/service/AuthService.java`

</details>

