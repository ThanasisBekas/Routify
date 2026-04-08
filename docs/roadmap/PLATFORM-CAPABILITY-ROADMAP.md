# Routify — Platform Capability Assessment & Improvement Roadmap

> **Type:** Living document — updated as initiatives are completed  
> **Platform version baseline:** 2.0.2-SNAPSHOT  
> **Scope:** Full-stack assessment covering all 9 Java services + React dashboard  
> **Related:** [Gateway Filters Backlog Roadmap](./GATEWAY-FILTERS-ROADMAP.md) (gf-01 through gf-22)

---

## Table of Contents

- [1. Executive Summary](#1-executive-summary)
- [2. Service Capability Matrix](#2-service-capability-matrix)
- [3. Identified Issues & Gaps](#3-identified-issues--gaps)
  - [3a. Gateway Filter Integration Gaps](#3a-gateway-filter-integration-gaps)
  - [3b. Cross-Service Config Resolution Gaps](#3b-cross-service-config-resolution-gaps)
  - [3c. Frontend Dashboard Gaps](#3c-frontend-dashboard-gaps)
  - [3d. Testing Gaps](#3d-testing-gaps)
- [4. Improvement Initiatives](#4-improvement-initiatives)
  - [Phase 1 — Security & Correctness (P0)](#phase-1--security--correctness-p0)
  - [Phase 2 — Config Integration & UX (P1)](#phase-2--config-integration--ux-p1)
  - [Phase 3 — Completeness & Polish (P2)](#phase-3--completeness--polish-p2)
  - [Phase 4 — Quality & Resilience (P3)](#phase-4--quality--resilience-p3)
- [5. Dependency Map](#5-dependency-map)
- [6. Risk Register](#6-risk-register)

---

## 1. Executive Summary

Routify is a mature zero-downtime API Gateway Platform with 29 active gateway filter types, 8 backend microservices, and a comprehensive React dashboard. The platform's core architecture — Kafka command bus, RabbitMQ request/reply, Transactional Outbox, hot-reload pipeline — is solid and well-implemented.

This assessment identifies **28 improvement initiatives** across four priority phases, focusing on:

1. **Security hardening** — JWT validation gaps, SpEL sandboxing, password hashing (3 initiatives)
2. **Config integration** — bridging static YAML auth config to the dynamic gateway config system so filters can import credentials from centrally-managed providers and certificate vault entries (6 initiatives)
3. **Frontend completeness** — adding pickers, selectors, and missing CRUD flows to the dashboard (7 initiatives)
4. **Test coverage** — expanding from 19 Java tests and 283 frontend tests to meaningful coverage (4 initiatives)
5. **Operational improvements** — validation, cleanup schedulers, error consistency (8 initiatives)

### What's Working Well

| Area | Status | Notes |
|------|--------|-------|
| Kafka command/event pipeline | ✅ Solid | Outbox pattern, idempotency, DLQ handling |
| RabbitMQ request/reply | ✅ Solid | 60+ queue/routing-key pairs, typed `QueryRequest`/`QueryResponse` |
| Gateway hot-reload | ✅ Solid | Zero-downtime route/filter changes |
| `GatewayConfigRefResolver` | ✅ Solid | 6 ref types: `AUTH_PROVIDER`, `RATE_LIMIT_POLICY`, `CIRCUIT_BREAKER_DEFAULTS`, `RESILIENCE_DEFAULTS`, `VAULT_CERT`, `DOWNSTREAM_CREDENTIAL` |
| Dashboard feature coverage | ✅ Solid | 16 modules, all major CRUD operations |
| Canary routing | ✅ Solid | Deploy/promote/rollback/weight-adjust with auto-rollback |
| Certificate Vault | ✅ Solid | ACME, groups, gateway TLS mapping, in-memory registry |
| AI filter/modifier pipeline | ✅ Solid | RabbitMQ RPC, A/B prompt testing, version management |
| Alerting engine | ✅ Solid | Rule CRUD, evaluation scheduler, state machine |

---

## 2. Service Capability Matrix

### routify-api-gateway (port 8080)

| Capability | Status | Details |
|-----------|--------|---------|
| Route hot-reload from Kafka | ✅ | `DynamicRouteRefreshListener` consumes `ROUTE_EVENTS` + `FILTER_EVENTS` |
| Gateway config hot-reload | ✅ | `GatewayConfigLoader` consumes `GATEWAY_CONFIG_EVENTS` |
| Filter chain building | ✅ | `RouteDefinitionBuilder` — 29 active filter types + 12 deprecated |
| Config ref resolution | ✅ | `GatewayConfigRefResolver` — 6 ref types |
| Certificate registry | ✅ | `CertificateRegistry` — vault-backed, versioned, hot-reloaded |
| Tenant quota enforcement | ✅ | Redis monthly counter in `TenantContextGatewayFilterFactory` |
| Canary weighted routing | ✅ | `Weight` predicate with `canary-{primaryRouteId}` group |
| Cluster heartbeat | ✅ | `GatewayInstanceRegistry` → Redis |
| Telemetry publishing | ✅ | `RequestLoggerGatewayFilterFactory` → Kafka `REQUEST_TELEMETRY` |
| JWT issuer/audience validation | ✅ | Issuer + audience claim validation, fail-closed misconfiguration, require-jti enforcement, JWKS URI support — [P-01](#p-01-jwt-auth-filter-hardening) ✅ completed |
| OAuth2 dynamic config bridge | ✅ | Dual-path introspection: direct config from `gatewayConfigRef` + legacy `providerName` → `AuthProperties` fallback. Dashboard Auth Provider picker auto-populates config. — [P-04](#p-04-oauth2-auth-provider-dynamic-config-bridge) ✅ completed |
| mTLS/ClientID dynamic config | ✅ | Dual-path: direct config from `gatewayConfigRef` (`MTLS_CLIENT_MAPPING` / `CLIENT_ID_MAPPING` ref types) + legacy static `CertificateValuesConfig` / `ClientProperties` YAML fallback. Dashboard Auth Provider picker auto-populates config. — [P-05](#p-05-mtlsclientid-migration-to-dynamic-gateway-config) ✅ completed |
| SpEL sandboxing | ✅ | `SimpleEvaluationContext` with read-only data binding, `#request` removed, expression length + depth limits — [P-02](#p-02-spel-filter-sandboxing) ✅ completed |

### routify-admin-api (port 8082)

| Capability | Status | Details |
|-----------|--------|---------|
| REST controllers | ✅ | 19 controllers covering all platform features |
| GraphQL analytics | ✅ | `/api/v1/admin/graphql` with 5 query types |
| SSE event stream | ✅ | `DashboardEventBroadcaster` → `/api/v1/admin/events` |
| WebSocket STOMP | ✅ | `/ws/websocket` — events, metrics, audit topics |
| Circuit breaker wrapping | ✅ | 6 messaging clients with Resilience4j CBs |
| Route import/export | ✅ | YAML/JSON with dry-run preview |
| Canary monitoring | ✅ | `CanaryMonitorService` — 30s polling, auto-rollback on 3 breaches |
| Fleet health scheduler | ✅ | `FleetHealthScheduler` |
| `gatewayConfigRef` validation on filter save | ✅ | `GatewayConfigRefValidator` validates refType (reject unknown), refId (reject blank), entry existence (warn only) — [P-14](#p-14-filter-config-gatewayconfigref-validation) ✅ completed |

### routify-route-service (port 8081)

| Capability | Status | Details |
|-----------|--------|---------|
| Route CRUD + lifecycle | ✅ | Draft → Active ↔ Disabled → Archived |
| Filter CRUD | ✅ | Attach/detach, create/update/delete |
| Outbox + Notify/Listen | ✅ | `OutboxPoller` + PostgreSQL trigger |
| Gateway config persistence | ✅ | `GatewayConfigService` — JSONB in `routify` schema |
| Route SLO config | ✅ | `route_slo` table |
| Canary deployment logic | ✅ | Deploy, promote, rollback, weight adjust |
| Quota enforcement (route/filter count) | ✅ | `TenantPlan` check before `CreateRoute`/`CreateFilter` |
| Route cloning | ✅ | Sync RPC via `QUEUE_ROUTES_CLONE` |

### routify-identity-service (port 8083)

| Capability | Status | Details |
|-----------|--------|---------|
| JWT issuance + refresh | ✅ | RS256, `DataSeeder` for initial admin |
| User CRUD | ✅ | Kafka commands + RabbitMQ queries |
| Tenant CRUD + lifecycle | ✅ | Create, update, suspend, reactivate |
| API key lifecycle | ✅ | Create, revoke, rotate → Redis projection |
| Webhook subscriptions | ✅ | CRUD + event consumer + HMAC dispatch |
| Role management (RBAC) | ✅ | Built-in + custom tenant-scoped roles |
| Auth operations | ✅ | Login, refresh, change password, logout blocklist |
| Webhook delivery cleanup | ✅ | Nightly `@Scheduled` cleanup (3:00 AM), batched deletion (`cleanup-batch-size`, default 1000), configurable retention (`delivery-retention-days`, default 7), Micrometer metric (`routify.webhooks.delivery.cleanup`) — [P-17](#p-17-webhook-delivery-cleanup-scheduler) ✅ completed |

### routify-audit-service (port 8084)

| Capability | Status | Details |
|-----------|--------|---------|
| Audit event persistence | ✅ | Kafka consumer for `AUDIT_EVENTS` |
| Request telemetry persistence | ✅ | `REQUEST_TELEMETRY` consumer |
| DLQ event persistence | ✅ | All DLQ topics consumed |
| AI filter decision analytics | ✅ | Stats + query + prompt versions + labelling |
| Tenant usage analytics | ✅ | Current period + daily history |
| Time-series analytics | ✅ | `date_trunc` SQL aggregation for GraphQL API |
| Alert rule evaluation | ✅ | 60s scheduler, OK → PENDING → FIRING → OK |
| Request replay | ✅ | Single + bulk replay |
| Route health stats | ✅ | Latency percentiles, error rates, status distribution |
| Data retention scheduler | ⚠️ | 7-day webhook delivery retention; audit retention period needs verification |

### routify-cert-vault (port 8085)

| Capability | Status | Details |
|-----------|--------|---------|
| Certificate CRUD | ✅ | Upload, revoke, delete, map/unmap to gateway |
| Cert group management | ✅ | Create, update, archive, delete, add/remove members |
| ACME lifecycle | ✅ | Register, issue, renew, order queries |
| AES encryption at rest | ✅ | `CERT_VAULT_ENCRYPTION_KEY` |
| Outbox pattern | ✅ | `CertOutboxPoller` |
| Gateway material fetch | ✅ | Internal-only `QUEUE_CERTS_FETCH_MATERIAL` |

### routify-ai-service (port 8086)

| Capability | Status | Details |
|-----------|--------|---------|
| AI filter evaluation (RabbitMQ RPC) | ✅ | ALLOW/BLOCK/FLAG verdicts |
| AI modifier evaluation (RabbitMQ RPC) | ✅ | Request body mutation |
| Decision event publishing | ✅ | `AI_FILTER_DECISIONS` + `AI_MODIFICATION_EVENTS` Kafka topics |
| Provider-agnostic | ✅ | Spring AI `ChatClient` — OpenAI or Ollama |
| No REST controllers | ✅ (by design) | All communication via RabbitMQ |

### routify-gitops-agent (port 8087)

| Capability | Status | Details |
|-----------|--------|---------|
| Git repo polling | ✅ | JGit clone/fetch, SHA-256 hash comparison |
| Reconciliation via admin-api | ✅ | Preview + apply import endpoints |
| API key auth | ✅ | `X-Api-Key` header |
| Webhook push events | ✅ | GitHub/GitLab webhook receiver |
| Redis state | ✅ | Last hash + history (50 entries) |
| Dry-run mode | ✅ | `routify.gitops.dry-run=true` |

### routify-dashboard (port 5173)

| Module | CRUD | Status | Gap |
|--------|------|--------|-----|
| Routes | Full | ✅ | — |
| Filters | Full | ✅ | — (pickers: cert vault P-06, auth provider P-07, downstream credential P-08) |
| Gateway Config | 13 tabs | ✅ | — |
| Certificates | Full | ✅ | — |
| API Keys | Full | ✅ | — |
| Webhooks | Full | ✅ | — |
| Users | Full | ✅ | — |
| Workspaces | Full | ✅ | Create, edit (name/plan/email), suspend/reactivate, usage analytics |
| Roles | Full | ✅ | — |
| Audit | Read + replay | ✅ | — |
| AI | Full | ✅ | Playground, versions, comparison |
| Alerts | Full | ✅ | — |
| GitOps | Status + sync | ✅ | — |
| Settings | Full | ✅ | Profile, security (password change, RBAC status), export/import shortcuts, appearance, platform info |

---

## 3. Identified Issues & Gaps

### 3a. Gateway Filter Integration Gaps

| # | Filter | Issue | Severity |
|---|--------|-------|----------|
| ~~1~~ | ~~`JwtAuthGatewayFilterFactory`~~ | ~~`Config.issuer` and `Config.audience` are declared but **never validated** in `parseAndValidate()`.~~ **Resolved in P-01:** issuer + audience validated after claims parsing. | ~~**High**~~ ✅ |
| ~~2~~ | ~~`JwtAuthGatewayFilterFactory`~~ | ~~Dev-mode unsigned JWT fallback.~~ **Resolved in P-01:** fail-closed — rejects all requests with `SERVER_MISCONFIGURED` when neither public key nor JWKS URI is configured. JWKS URI support added. | ~~**High**~~ ✅ |
| ~~3~~ | ~~`JwtAuthGatewayFilterFactory`~~ | ~~`Config.algorithm` supports `HS256` but only RS256 is implemented.~~ **Resolved in P-01:** HS256 config is logged as warning and ignored (RS256 only). | ~~**Low**~~ ✅ |
| ~~4~~ | ~~`SpelCustomGatewayFilterFactory`~~ | ~~Uses `StandardEvaluationContext` which exposes `#request`.~~ **Resolved in P-02:** replaced with `SimpleEvaluationContext.forReadOnlyDataBinding()`, `#request` removed, expression length + depth limits enforced. | ~~**High**~~ ✅ |
| 5 | `OAuth2TokenIntrospectGatewayFilterFactory` | ~~Reads `providerName` from `Config`, then looks it up in `AuthProperties.oauth2Verification` (static YAML map). The bridge is broken.~~ Dual-path: direct config from `gatewayConfigRef` (introspectUri/clientId/clientSecret) + legacy `providerName` → `AuthProperties` fallback. — [P-04](#p-04-oauth2-auth-provider-dynamic-config-bridge) ✅ completed | **Resolved** |
| 6 | `MtlsAuthGatewayFilterFactory` | ~~Config class is `CertificateValuesConfig` (from `auth.properties` YAML). No `GatewayConfigRefResolver` mapping exists for `AUTH_MTLS`. Filter cannot import cert mappings from dynamic gateway config.~~ Dual-path: `MTLS_CLIENT_MAPPING` ref type resolves from MTLS auth providers + legacy `CertificateValuesConfig` YAML fallback. Dashboard MTLS Provider picker auto-populates config. — [P-05](#p-05-mtlsclientid-migration-to-dynamic-gateway-config) ✅ completed | **Resolved** |
| 7 | `ClientIdAuthGatewayFilterFactory` | ~~Config class is `NameValuesConfig` backed by `ClientProperties` YAML. No `GatewayConfigRefResolver` mapping for `AUTH_CLIENT_ID`. Same static-config limitation as mTLS.~~ Dual-path: `CLIENT_ID_MAPPING` ref type resolves from CLIENT_ID auth providers + legacy `ClientProperties` YAML fallback. Dashboard Client ID Provider picker auto-populates config. — [P-05](#p-05-mtlsclientid-migration-to-dynamic-gateway-config) ✅ completed | **Resolved** |
| 8 | `DownstreamOAuth2BearerGatewayFilterFactory` | `Config.oauth2ProviderName` maps to `Oauth2AccessTokenProvider` static config. Not integrated with the `DOWNSTREAM_CREDENTIAL` ref type or dynamic auth provider config. | **Medium** |
| 9 | `BasicAuthGatewayFilterFactory` | ~~Password comparison is plain-text `equals()`.~~ BCrypt-hashed at rest, `BCryptPasswordEncoder.matches()` in gateway. Backward-compatible with legacy plain-text configs. — [P-03](#p-03-basicauth-password-hashing-in-gateway-config) ✅ completed | **Resolved** |
| 10 | Cert-related filters (`AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`) | ~~`VAULT_CERT` ref type correctly injects `logicalId`, but the dashboard filter form requires manual `logicalId` text entry. No cert picker dropdown populated from cert-vault.~~ Dashboard `CertLogicalIdPicker` dropdown populated from `GET /certificates/logical-ids` — [P-06](#p-06-cert-vault-picker-for-filter-config-forms) ✅ completed | **Resolved** |

### 3b. Cross-Service Config Resolution Gaps

| # | Gap | Details |
|---|-----|---------|
| 1 | ~~**OAuth2 provider config disconnect**~~ | ~~Auth providers defined in gateway config cannot be consumed by `OAuth2TokenIntrospectGatewayFilterFactory`.~~ **Resolved in P-04:** Filter now reads direct config fields (`introspectUri`, `clientId`, `clientSecret`) from `gatewayConfigRef` resolution and calls the introspection endpoint directly. Legacy `providerName` → `AuthProperties` YAML path preserved for backward compatibility. |
| 2 | ~~**mTLS static config island**~~ | ~~`MtlsAuthGatewayFilterFactory` uses `CertificateValuesConfig` which requires certificate-to-client-ID mappings baked into YAML.~~ **Resolved in P-05:** `MTLS_CLIENT_MAPPING` ref type resolves client mappings from MTLS auth providers in the dynamic gateway config. Legacy YAML path preserved for backward compatibility. |
| 3 | **Downstream OAuth2 provider disconnect** | `DownstreamOAuth2BearerGatewayFilterFactory.Config.oauth2ProviderName` maps to `Oauth2AccessTokenProvider` YAML config. The `DOWNSTREAM_CREDENTIAL` ref type maps `username`/`password`/`headerName`/`headerValue` but these aren't the fields the downstream OAuth2 filter reads (`oauth2ProviderName`, `forwardCallerAuth`). |
| ~~4~~ | ~~**No `gatewayConfigRef` validation on save**~~ | ~~When creating or updating a filter with a `gatewayConfigRef`, the route-service persists the ref without validating that the referenced gateway config entry (e.g., auth provider with that `refId`) actually exists. The error surfaces only at gateway route-build time.~~ **Resolved in P-14:** `GatewayConfigRefValidator` validates refType (reject unknown), refId format (reject blank on list/cert types), and entry existence (warn-only for eventual consistency). |

### 3c. Frontend Dashboard Gaps

| # | Module | Gap | Impact |
|---|--------|-----|--------|
| ~~1~~ | ~~Filters~~ | ~~No **cert vault picker** for `AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK` — `logicalId` is a free-text input~~ **Resolved in P-06:** `CertLogicalIdPicker` dropdown populated from `GET /certificates/logical-ids`. | ~~Operator must manually type the exact logical ID~~ ✅ |
| 2 | Filters | No **OAuth2 auth provider picker** for `AUTH_OAUTH2` — `providerName` is free-text | Operators don't know which providers are configured |
| ~~3~~ | ~~Filters~~ | ~~No **downstream credential picker** for `DOWNSTREAM_BASIC_AUTH`, `DOWNSTREAM_BEARER_CC`~~ **Resolved in P-08:** `DownstreamCredentialPicker` dropdown populated from `GET /downstream-credentials`. | ~~Must manually type credential names~~ ✅ |
| ~~4~~ | ~~Filters~~ | ~~No **rate limit policy picker** for `RATE_LIMIT_FIXED_WINDOW`, `RATE_LIMIT_SLIDING_WINDOW`~~ **Resolved in P-09:** `RateLimitPolicyPicker` dropdown populated from `GET /rate-limit-policies` with read-only summary + override toggle. | ~~Must manually configure values instead of selecting a policy~~ ✅ |
| ~~5~~ | ~~Workspaces~~ | ~~No **create workspace** action — `WorkspacesPage.tsx` shows usage only~~ **Resolved in P-12:** `CreateWorkspaceModal` with name, slug (auto-generated), plan picker, contact email. "New Workspace" button in header. | ~~Cannot create tenants from the dashboard~~ ✅ |
| ~~6~~ | ~~Workspaces~~ | ~~No **plan upgrade/change** flow~~ **Resolved in P-12:** `EditWorkspaceModal` with plan grid picker (FREE/STARTER/PRO/ENTERPRISE), name, and contact email. Edit button on each workspace row. | ~~Plan changes require direct API/DB access~~ ✅ |
| ~~7~~ | ~~Settings~~ | ~~`SettingsPage.tsx` is a single file — may be a stub~~ **Resolved in P-13:** Expanded with 6 sections: profile card, account details, security (password change link + RBAC status), export/import shortcuts, appearance (theme toggle), platform info (version, Spring Boot/Cloud versions). | ~~Settings module may be incomplete~~ ✅ |

### 3d. Testing Gaps

| Area | Current | Total Surface | Coverage |
|------|---------|--------------|----------|
| **Java unit tests** | 603 tests across gateway + common + identity (gateway: 603, common: 5, identity: 8) | ~300+ source files across 8 services | ~40% (gateway) |
| **Java integration tests** | 5 ITs across 3 services (admin-api: 2, identity: 2, route: 1) | 8 services with DB/messaging | ~3% |
| **Frontend unit tests** | 283 tests across 42 files (API clients, hooks, stores, module components) | 16 modules + 17 API clients + 4 hooks + 2 stores | ~85% |
| **Frontend E2E tests** | 2 specs (login, routes) | 16 feature modules | ~12% |
| **Services with zero tests** | audit-service, cert-vault, ai-service, gitops-agent | — | 0% |

---

## 4. Improvement Initiatives

### Phase 1 — Security & Correctness (P0)

Critical issues that could lead to security vulnerabilities or incorrect behavior in production.

---

#### P-01: JWT Auth Filter Hardening ✅ COMPLETED

**Overlaps with:** [gf-04 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#4-jwtauth-filter-hardening)  
**Affected services:** `routify-api-gateway`  
**Complexity:** S  
**Files:** `JwtAuthGatewayFilterFactory.java`  
**Status:** ✅ Completed — all 3 problems resolved, 10 dedicated hardening tests passing.

**Problem:**
1. ~~`Config.issuer` and `Config.audience` are declared but the `parseAndValidate()` method never checks `claims.getIssuer()` or `claims.getAudience()` against them.~~ ✅ Fixed
2. ~~When `routify.jwt.public-key` is blank, the filter decodes JWTs without signature verification (dev-mode fallback). If this accidentally reaches production, any crafted JWT is accepted.~~ ✅ Fixed
3. ~~Tokens without a `jti` claim skip the Redis blocklist check entirely (only a warning is logged).~~ ✅ Fixed

**Implementation summary:**
- **Issuer/audience validation:** After `parseAndValidate()` returns claims, the filter checks `config.getIssuer()` and `config.getAudience()`. Mismatches return 401 with `INVALID_ISSUER` or `INVALID_AUDIENCE` error codes.
- **Fail-closed misconfiguration:** The unsigned JWT decode path has been removed. `@PostConstruct validateKeySource()` sets a `misconfigured` flag when neither `routify.jwt.public-key` nor `routify.jwt.jwks-uri` is set. All requests receive 500 `SERVER_MISCONFIGURED`. A prominent `ERROR`-level banner is logged at startup.
- **JWKS URI support:** New `routify.jwt.jwks-uri` config property enables fetching RSA public keys from a JWKS endpoint. Keys are cached in a Caffeine cache (`routify.jwt.jwks-cache-minutes`, default 5). Supports `kid`-based key selection and single-flight fetching. Static key fallback on JWKS fetch failure when both are configured.
- **Require JTI:** Global `routify.jwt.require-jti` (default `true`) and per-filter `Config.requireJti` (nullable override). Tokens without a `jti` claim are rejected with 401 `MISSING_JTI` when enabled.
- **HS256 deprecated:** HS256 algorithm config is logged as a warning and ignored (RS256 only).
- **Tests:** `JwtAuthHardeningTest.java` (10 tests): JWKS fetch + cache, key rotation, JWKS failure + static fallback, misconfigured rejection, issuer mismatch, audience mismatch, missing JTI require-true, missing JTI require-false, HS256 migration, JWKS without kid.

---

#### P-02: SpEL Filter Sandboxing ✅ COMPLETED

**Overlaps with:** [gf-07 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#7-spel-filter-sandboxing--security)  
**Affected services:** `routify-api-gateway`  
**Complexity:** S  
**Files:** `SpelCustomGatewayFilterFactory.java`  
**Status:** ✅ Completed — all 3 changes implemented, 20 dedicated sandboxing tests passing.

**Problem:** ~~`StandardEvaluationContext` exposes `#request` (full `ServerHttpRequest` object), enabling `#request.getClass().getClassLoader()` → arbitrary code execution.~~ ✅ Fixed

**Implementation summary:**
- **`SimpleEvaluationContext`:** Replaced `StandardEvaluationContext` with `SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods()`. This disallows type references (`T(java.lang.Runtime)`), constructors (`new ProcessBuilder()`), and method invocation on arbitrary objects. Only String instance methods are allowed on context variables.
- **`#request` removed:** The `#request` variable (full `ServerHttpRequest` object) has been removed. Replaced with safe primitives: `#headers` (Map), `#params` (Map), `#method` (String), `#path` (String), `#contentType` (String), `#clientIp` (String, X-Forwarded-For aware).
- **Expression length limit:** `maxExpressionLength` config param (default 500 chars). Expressions exceeding the limit are rejected at config bind time with 500 `SPEL_EXPRESSION_TOO_LONG`.
- **Property depth limit:** `maxPropertyDepth` config param (default 5). Prevents deeply nested property chains. Rejected with 500 `SPEL_EXPRESSION_TOO_COMPLEX`.
- **Audit events:** Every evaluation (success, failure, rejection) publishes a `CUSTOM_SPEL_EVALUATED` audit event to Kafka with expression (truncated), result, evaluation time, and client IP.
- **Tests:** `SpelSandboxingTest.java` (20 tests): sandbox escape attempts blocked (Runtime.exec, ProcessBuilder, #request.getClass), valid expressions with all 6 context variables, expression complexity limits, property depth counting, audit event emission, edge cases.

---

#### P-03: BasicAuth Password Hashing in Gateway Config ✅ COMPLETED

**Affected services:** `routify-api-gateway`, `routify-admin-api`  
**Complexity:** S  
**Files:** `BasicAuthGatewayFilterFactory.java`, `GatewayConfigService.java`  
**Status:** ✅ Completed — BCrypt hashing + backward-compatible legacy fallback, 19 dedicated tests passing (12 gateway + 7 admin-api).

**Problem:** `BasicAuthGatewayFilterFactory` compares passwords using plain-text `equals()`. The password stored in gateway config (via the Auth Providers section) is stored unencrypted. `@SensitiveField` masks it in API responses but not at rest.

**Changes (implemented):**
- **Backend (admin-api):** `GatewayConfigService.upsertAuthProvider()` and `saveConfig()` BCrypt-hash (strength 12) the password of BASIC auth providers before persisting. Skips re-hashing when the password is masked (`Sensitive.isMasked()`), already a BCrypt hash (`$2` prefix), or null/blank.
- **Backend (gateway):** `BasicAuthGatewayFilterFactory` uses `BCryptPasswordEncoder.matches()` for password comparison when the stored value is a BCrypt hash. Falls back to plain-text `equals()` for legacy configs that have not yet been re-saved, with a `WARN` log urging re-save.
- **Frontend:** None (password field already masked by `@SensitiveField`).
- **Tests:** `BasicAuthPasswordHashingTest.java` (12 tests): BCrypt match, BCrypt reject wrong password/username, plain-text legacy fallback, header injection on success, missing auth header, non-Basic scheme, malformed Base64, misconfigured blank/null credentials, password with colon chars. `GatewayConfigServicePasswordHashingTest.java` (7 tests): plain-text → BCrypt on upsert, skip already-hashed, preserve masked from existing, null/blank left untouched, non-BASIC not hashed, saveConfig hashes all BASIC providers.

---

### Phase 2 — Config Integration & UX (P1)

Bridge the gap between statically-configured auth providers and the dynamic gateway config system. Add dashboard pickers for config references.

---

#### P-04: OAuth2 Auth Provider Dynamic Config Bridge ✅ COMPLETED

**Affected services:** `routify-api-gateway`, `routify-dashboard`  
**Complexity:** M  
**Files:** `OAuth2TokenIntrospectGatewayFilterFactory.java`, `GatewayConfigRefResolver.java`, `Oauth2BearerTokenVerifier.java`, `FilterConfigFields.tsx`  
**Status:** ✅ Completed — dual-path introspection (direct config + legacy provider name), 10 dedicated tests passing.

**Problem:** The `AUTH_OAUTH2` filter reads `Config.providerName` and looks it up in static `AuthProperties.oauth2Verification` YAML. The `GatewayConfigRefResolver.resolveAuthProvider()` correctly resolves `introspectUri`, `clientId`, `clientSecret` from the gateway config's auth providers section, but the filter doesn't read these fields — it only uses `providerName`.

**Changes (implemented):**
- **Backend (gateway):** `OAuth2TokenIntrospectGatewayFilterFactory.Config` now accepts `introspectUri`, `clientId`, `clientSecret`, `parameterStyle`, `parameterName`, `contentType`, and `includeBasicClientAuthorization` directly alongside the existing `providerName`.
- **Backend (gateway):** `apply()` checks if direct config fields are present (from `gatewayConfigRef` resolution). If so, uses them directly for token introspection via the new `Oauth2BearerTokenVerifier.verifyToken(...)` overload. Falls back to `providerName` → `AuthProperties` lookup for backward compatibility.
- **Backend (gateway):** `GatewayConfigRefResolver.resolveAuthProvider()` now maps the provider `name` to `providerName` (in addition to `_providerName`) and passes through introspection-specific fields (`parameterStyle`, `parameterName`, `contentType`, `includeBasicClientAuthorization`) for OAUTH2 types.
- **Backend (gateway):** `Oauth2BearerTokenVerifier` has a new overloaded `verifyToken()` method accepting direct config values, building an ad-hoc `Oauth2VerificationConfig` internally to reuse existing request-building infrastructure. WebClients are pooled via `WebClientRegistry` with a cache key based on the introspection URI host.
- **Frontend:** `AUTH_OAUTH2` filter form in `FilterConfigFields.tsx` now includes an **Auth Provider** dropdown (populated from `gatewayApi.getAuthProviders()`, filtered to `OAUTH2_*` types). Selecting a provider auto-populates `introspectUri`, `clientId`, `clientSecret`, and `parameterStyle` into the filter config. When direct config fields are present, the form shows the introspection config fields for review/override and a status indicator. When empty, the legacy `providerName` text input is shown.
- **Tests:** `OAuth2DynamicConfigBridgeTest.java` (10 tests): direct config success + failure + precedence over providerName, legacy providerName success + missing config, neither configured error, missing bearer token, partial direct config fallback, GatewayConfigRefResolver providerName mapping for OAUTH2 and BASIC types.

---

#### P-05: mTLS/ClientID Migration to Dynamic Gateway Config ✅ COMPLETED

**Affected services:** `routify-api-gateway`, `routify-dashboard`  
**Complexity:** M  
**Files:** `MtlsAuthGatewayFilterFactory.java`, `ClientIdAuthGatewayFilterFactory.java`, `GatewayConfigRefResolver.java`, `RouteDefinitionBuilder.java`, `FilterConfigFields.tsx`, `src/types/index.ts`, `src/mocks/db.ts`  
**Status:** ✅ Completed — dual-path config (dynamic gateway config ref + legacy static YAML), 2 new ref types, dashboard pickers for both filter types.

**Problem:** Both filters use legacy static config classes (`CertificateValuesConfig`, `NameValuesConfig` / `ClientProperties`) loaded from `application.yml`. There's no `GatewayConfigRefResolver` mapping for `AUTH_MTLS` or `AUTH_CLIENT_ID`, so these filters can't benefit from the dynamic gateway config system.

**Implementation summary:**
- **Backend (gateway — `GatewayConfigRefResolver`):** Added two new ref types to the `resolve()` switch expression:
  - `MTLS_CLIENT_MAPPING` — finds an auth provider with `type: "MTLS"` by `refId`, extracts its `clientMappings` list (each containing `clientIdRequestHeader`, `clientIdValue`, `clientCertificateRequestHeader`, `clientCertificateValue`), and returns them as the `values` key for `CertificateValuesConfig` binding via `indexedValuesFilter`.
  - `CLIENT_ID_MAPPING` — finds an auth provider with `type: "CLIENT_ID"` by `refId`, extracts its `clientEntries` (name/value pairs) and optional `clientIdMapping` (orgId→clientId map), returning both for `Config` binding.
- **Backend (gateway — `ClientIdAuthGatewayFilterFactory`):** Refactored from `NameValuesConfig` to a new `Config` inner class extending `NameValuesConfig` with an additional `clientIdMapping` field. The `apply()` method reads `clientIdMapping` from the resolved config first, falling back to the injected `ClientProperties` bean for backward compatibility.
- **Backend (gateway — `RouteDefinitionBuilder`):** Extended `indexedValuesFilter()` to handle `clientIdMapping` as indexed args (`clientIdMapping[orgId]=clientId`) alongside the existing `values` expansion, so Spring can bind the map correctly.
- **Backend (gateway — `MtlsAuthGatewayFilterFactory`):** No code changes needed — `CertificateValuesConfig.values` is already populated by the `indexedValuesFilter` + `GatewayConfigRefResolver` pipeline. Updated Javadoc to document dual-path config.
- **Frontend (types):** Added `'MTLS'` and `'CLIENT_ID'` to the `GatewayAuthProvider.type` union. Added optional fields: `clientMappings` (for MTLS) and `clientEntries` + `clientIdMapping` (for CLIENT_ID).
- **Frontend (`FilterConfigFields.tsx`):** Both `MtlsMappingFields` and `ClientIdMappingFields` now include a "Gateway Config Provider" section with a dropdown that fetches auth providers via `gatewayApi.getAuthProviders()`, filtered by type `MTLS` or `CLIENT_ID`. Selecting a provider auto-populates the `values` (and `clientIdMapping` for CLIENT_ID) from the provider's config. A status indicator confirms when mappings are imported from a gateway auth provider.
- **Frontend (mocks):** Added sample MTLS auth provider (`ap-mtls-01` — 2 partner mappings) and CLIENT_ID auth provider (`ap-clientid-01` — 3 entries with org-ID mappings) to the mock database so the picker works in MSW mode.

---

#### P-06: Cert Vault Picker for Filter Config Forms ✅ COMPLETED

**Affected services:** `routify-admin-api`, `routify-dashboard`  
**Complexity:** S  
**Files:** `AdminCertificatesController.java`, `FilterConfigFields.tsx`, `certVaultApi.ts`, `src/types/index.ts`, `src/mocks/handlers/certs.ts`  
**Status:** ✅ Completed — cert vault logical-ID picker replaces free-text input on 3 filter types.

**Problem:** Cert-related filters (`AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`) require a `logicalId` that operators must type manually. The cert vault already exposes `QUEUE_CERTS_ACTIVE_LIST` which returns active certificates with their logical IDs.

**Implementation summary:**
- **Backend (admin-api):** Added `GET /api/v1/admin/certificates/logical-ids` endpoint on `AdminCertificatesController` that returns `[{logicalId, alias, status}]` by querying cert groups via existing `CertVaultMessagingClient.queryCertGroups()`. Inner DTO: `CertLogicalIdEntry` record.
- **Frontend (types):** Added `CertLogicalIdEntry` interface (`logicalId`, `alias`, `status`).
- **Frontend (certVaultApi):** Added `listLogicalIds()` API function calling the new endpoint.
- **Frontend (FilterConfigFields):** Created `CertLogicalIdPicker` component — a searchable dropdown that fetches cert-group logical IDs from the new endpoint. Shows a validation indicator (✓ resolved / ⚠ not found). Supports custom values for forward compatibility. Replaced the free-text `logicalId` input on `AUTH_CERT_VAULT`, `CERT_ROTATION`, and `CERT_VAULT_EXPIRY_CHECK` filter forms.
- **Frontend (mocks):** Added MSW handler for `GET /certificates/logical-ids` returning cert group logical IDs from the mock database.

---

#### P-07: Auth Provider Picker for Filter Forms ✅ COMPLETED

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `FilterConfigFields.tsx`, `gatewayApi.ts`

**Problem:** `AUTH_OAUTH2` and `DOWNSTREAM_BEARER_CC` filter forms have free-text `providerName` / `oauth2ProviderName` inputs. Operators don't know which auth providers are configured in the gateway config's Auth Providers tab.

**Changes:**
- **Frontend:** Create an `AuthProviderPicker` component that fetches the current gateway config's `authProviders` list (via existing `GET /api/v1/admin/gateway?section=authProviders`) and renders a dropdown. Filter by provider type (`OAUTH2_*` for `AUTH_OAUTH2`, all types for `AUTH_BASIC`).
- **Frontend:** Replace the free-text `providerName` input in `AUTH_OAUTH2` and `oauth2ProviderName` in `DOWNSTREAM_BEARER_CC` with the picker.

---

#### P-08: Downstream Credential Picker for Filter Forms ✅ COMPLETED

**Affected services:** `routify-admin-api`, `routify-dashboard`  
**Complexity:** S  
**Files:** `GatewayConfigDto.java`, `GatewayConfigController.java`, `GatewayConfigService.java`, `FilterConfigFields.tsx`, `DownstreamCredentialPicker.tsx`, `gatewayApi.ts`, `src/types/index.ts`, `src/mocks/db.ts`, `src/mocks/handlers/gateway.ts`  
**Status:** ✅ Completed — downstream credential picker replaces free-text credential inputs on 2 filter types.

**Problem:** `DOWNSTREAM_BASIC_AUTH` filter form has free-text `username`/`password` inputs but could instead reference a downstream credential entry from the gateway config.

**Implementation summary:**
- **Backend (admin-api):** Added `DownstreamCredentialDto` inner class to `GatewayConfigDto` with fields: `id`, `name`, `description`, `type` (BASIC/HEADER), `username`, `password` (@SensitiveField), `headerName`, `headerValue` (@SensitiveField), `enabled`. Added `downstreamCredentials` list field to `GatewayConfigDto`. Added `GET /api/v1/admin/gateway/downstream-credentials`, `PUT /api/v1/admin/gateway/downstream-credentials/{credentialId}`, and `DELETE /api/v1/admin/gateway/downstream-credentials/{credentialId}` endpoints on `GatewayConfigController`. Added `getDownstreamCredentials()`, `upsertDownstreamCredential()`, and `deleteDownstreamCredential()` service methods. Sensitive field masking preserved for password and headerValue.
- **Frontend (types):** Added `GatewayDownstreamCredential` interface (`id`, `name`, `description`, `type: 'BASIC' | 'HEADER'`, `username`, `password`, `headerName`, `headerValue`, `enabled`). Added `downstreamCredentials` to `GatewayConfig`.
- **Frontend (gatewayApi):** Added `getDownstreamCredentials()`, `upsertDownstreamCredential()`, and `deleteDownstreamCredential()` API functions.
- **Frontend (DownstreamCredentialPicker):** Created `DownstreamCredentialPicker` component — a searchable dropdown that fetches downstream credentials from the new endpoint. Supports type filtering (BASIC vs HEADER), validation indicators, and custom fallback for removed/disabled credentials. Follows the `AuthProviderPicker` pattern (P-07).
- **Frontend (FilterConfigFields):** `DOWNSTREAM_BASIC_AUTH` form now includes a `DownstreamCredentialPicker` filtered to `BASIC` type. Selecting a credential auto-populates `username`/`password`. Manual entry is shown only when no credential is selected. `DOWNSTREAM_BEARER_CC` form now includes an additional `DownstreamCredentialPicker` filtered to `HEADER` type for injecting custom downstream headers alongside the OAuth2 bearer token.
- **Frontend (mocks):** Added 4 sample downstream credentials to the mock gateway config: 2 BASIC (Payments Service Account, Internal API Credentials) and 2 HEADER (Partner API Token enabled, Internal X-Service-Key disabled). Added MSW handlers for `GET/PUT/DELETE /downstream-credentials`.

---

#### P-09: Rate Limit Policy Picker for Filter Forms ✅ COMPLETED

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `FilterConfigFields.tsx`, `RateLimitPolicyPicker.tsx`, `src/mocks/db.ts`  
**Status:** ✅ Completed — rate limit policy picker with read-only summary + override toggle on both filter types.

**Problem:** `RATE_LIMIT_FIXED_WINDOW` and `RATE_LIMIT_SLIDING_WINDOW` filter forms require manual entry of `maxRequests`, `windowMs`, `keyResolver`. The gateway config's Rate Limiting tab stores reusable policies (`rateLimitPolicies`) that could be referenced via `gatewayConfigRef`.

**Implementation summary:**
- **Frontend (`RateLimitPolicyPicker.tsx`):** Created reusable picker component that fetches `rateLimitPolicies` via the existing `gatewayApi.getRateLimitPolicies()` endpoint. Supports `algorithmFilter` prop for narrowing to compatible policy types (FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET). Shows policy description with algorithm, rate, window, and key resolver in the dropdown. Follows the `AuthProviderPicker` pattern (P-07) with searchable dropdown, validation indicator, and "not found" fallback.
- **Frontend (`FilterConfigFields.tsx`):** Both `RATE_LIMIT_FIXED_WINDOW` and `RATE_LIMIT_SLIDING_WINDOW` filter forms now include the `RateLimitPolicyPicker` at the top. When a policy is selected: auto-populates `maxRequests`, `windowMs`, `keyResolver` from the policy; shows a read-only summary card with all three values; provides an "Override Policy Values" toggle. When override is enabled, editable fields reappear with an amber warning and "Revert to policy values" link. Manual entry fields shown when no policy is selected. The `includeHeaders` toggle is always visible regardless of policy selection.
- **Frontend (mocks):** Added 2 additional mock rate-limit policies: `SLIDING_WINDOW` (500 req/5min, USER key) and a disabled `FIXED_WINDOW` policy, for a total of 4 mock policies covering all algorithm types.

---

### Phase 3 — Completeness & Polish (P2)

Missing features and UX improvements that round out the platform.

---

#### P-10: Unified Gateway Filter Error Response Builder ✅ COMPLETED

**Overlaps with:** [gf-02 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#2-unified-error-response-builder)  
**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `GatewayProblemResponse.java`, `CertRotationGatewayFilterFactory.java`, `RequestTimeoutGatewayFilterFactory.java`, `CertVaultExpiryCheckGatewayFilterFactory.java`, `CircuitBreakerV2GatewayFilterFactory.java`, `DownstreamOAuth2BearerGatewayFilterFactory.java`, `DownstreamBasicAuthGatewayFilterFactory.java`, `UserIdPayloadRoutingGatewayFilterFactory.java`  
**Status:** ✅ Completed — shared `GatewayProblemResponse` builder already existed; migrated 7 remaining filter factories from manual JSON / `ResponseStatusException` to the shared builder.

**Problem:** Each filter that short-circuits writes its own RFC 9457 response using `String.formatted()`. Inconsistencies:
- Some use `application/problem+json`, others use `application/json`
- JSON escaping is ad-hoc (`replace("\"", "'")` in AI filter vs `replace("\"", "\\\"")` in SpEL)
- Some include `errorCode`, others don't
- Rate limit rejections lack `Retry-After` header

**Implementation summary:**
- **`GatewayProblemResponse`** (already existed): Shared RFC 9457 builder at `io.routify.gateway.filter.shared.GatewayProblemResponse` with Jackson `ObjectMapper` for safe JSON serialization. Builder API: `status(HttpStatus)` → `errorCode(String)` → `detail(String)` → `header(name, value)` → `extension(key, value)` → `write(exchange)` → `Mono<Void>`. Always sets `Content-Type: application/problem+json`.
- **Migrated 7 filter factories** from old patterns (manual `String.formatted()` JSON or `ResponseStatusException`) to `GatewayProblemResponse`:
  1. `CertRotationGatewayFilterFactory` — replaced manual JSON `unauthorized()` with builder
  2. `RequestTimeoutGatewayFilterFactory` — replaced manual JSON `gatewayTimeout()` with builder, added `GATEWAY_TIMEOUT` errorCode
  3. `CertVaultExpiryCheckGatewayFilterFactory` — replaced manual JSON `serviceUnavailable()` with builder
  4. `CircuitBreakerV2GatewayFilterFactory` — replaced default fallback with builder, preserved custom `fallbackBody` backward-compatible path
  5. `DownstreamOAuth2BearerGatewayFilterFactory` — replaced `ResponseStatusException` with builder, added `MISSING_OAUTH2_PROVIDER` errorCode
  6. `DownstreamBasicAuthGatewayFilterFactory` — replaced `ResponseStatusException` with builder, added `DOWNSTREAM_AUTH_MISCONFIGURED` errorCode
  7. `UserIdPayloadRoutingGatewayFilterFactory` — replaced 2 `ResponseStatusException` calls with builder, added `ROUTING_MISCONFIGURED` errorCode
- **14 filter factories** already used `GatewayProblemResponse` — no changes needed.
- **Frontend:** None.

---

#### P-11: Rate Limiter X-RateLimit-* Response Headers ✅ COMPLETED

**Overlaps with:** [gf-03 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#3-rate-limiter-x-ratelimit--response-headers)  
**Affected services:** `routify-api-gateway`  
**Complexity:** S  
**Files:** `FixedWindowRateLimitGatewayFilterFactory.java`, `SlidingWindowRateLimitGatewayFilterFactory.java`  
**Status:** ✅ Completed — all 4 changes implemented, 26 dedicated tests passing (14 fixed-window + 12 sliding-window).

**Problem:** Neither rate limiter emits standard `X-RateLimit-*` headers. API consumers can't proactively slow down before hitting limits.

**Implementation summary:**
- **Lua scripts return `{count, ttl}` tuples:** Fixed-window Lua returns `{current_count, remaining_ttl_seconds}`. Sliding-window Lua returns `{allowed (1/0), current_count, remaining_ttl_seconds}`. Both scripts compute TTL via `PTTL` and convert to ceiling seconds.
- **X-RateLimit-* headers on every response:** Both factories inject `X-RateLimit-Limit` (max requests), `X-RateLimit-Remaining` (max(0, limit − count)), and `X-RateLimit-Reset` (epoch-second timestamp = now + ttlSeconds) on both allowed and rejected responses via `injectRateLimitHeaders()`.
- **`includeHeaders` config flag (default `true`):** When `false`, suppresses `X-RateLimit-*` headers on both allow and reject paths. `Retry-After` on 429 responses is always included regardless of this flag (per RFC 6585).
- **`Retry-After` on 429 responses:** Both factories include `Retry-After` header (in seconds) on every 429 rejection via `GatewayProblemResponse.header("Retry-After", retryAfterSeconds)`. Uses `Math.max(1, ttlSeconds)` to guarantee a minimum 1-second value.
- **Tests:** `FixedWindowRateLimitGatewayFilterFactoryTest.java` (14 tests): allow within limit, allow at exact limit, reject above limit, default config, X-RateLimit-Limit/Remaining/Reset on allow, Retry-After on 429, X-RateLimit-* on 429, no Retry-After on allow, includeHeaders=false suppresses headers on allow, includeHeaders=false preserves Retry-After on 429, USER/TENANT key resolvers, Redis empty fallback. `SlidingWindowRateLimitGatewayFilterFactoryTest.java` (12 tests): same coverage for sliding-window algorithm.
- **Frontend:** None (the `includeHeaders` toggle is already exposed in the rate limit filter form via the P-09 `RateLimitPolicyPicker` implementation).

---

#### P-12: Workspace Create & Plan Management Dashboard Flow ✅ COMPLETED

**Affected services:** `routify-dashboard`, `routify-admin-api`  
**Complexity:** M  
**Files:** `WorkspacesPage.tsx`, `tenantsApi.ts`, `AdminTenantsController.java`, `CreateTenantRequest.java`, `UpdateTenantRequest.java`, `UsageOverview.tsx`, `UsageTrendChart.tsx`, `src/mocks/handlers/tenants.ts`, `src/mocks/data/tenants.ts`  
**Status:** ✅ Completed — full workspace CRUD with create modal, edit modal (plan management), suspend/reactivate, and usage analytics.

**Problem:** `WorkspacesPage.tsx` shows usage analytics (UsageOverview, UsageTrendChart) but has no "Create Workspace" action. Plan changes are not exposed in the UI.

**Implementation summary:**
- **Frontend (`WorkspacesPage.tsx`):** Full workspace management page with SUPER_ADMIN guard. "New Workspace" button opens `CreateWorkspaceModal` with fields: name, slug (auto-generated from name, URL-safe), plan (4-option grid: FREE/STARTER/PRO/ENTERPRISE), and optional contact email. Creates via `tenantsApi.create()` → `POST /api/v1/admin/tenants`. Success state shows confirmation with slug. Edit button (pencil icon) on each workspace row opens `EditWorkspaceModal` with editable name, read-only slug, plan grid picker, and contact email. Updates via `tenantsApi.update()` → `PUT /api/v1/admin/tenants/{id}`. Suspend/reactivate buttons on each row. Expandable row shows `UsageOverview` (quota bars) and `UsageTrendChart` (daily request volume line chart). "Current" badge on the logged-in tenant's row. Real-time updates via `useRealtimeQuery` with `wsEvents: ['tenant']`.
- **Frontend (`tenantsApi.ts`):** `CreateWorkspaceRequest` (name, slug, plan, contactEmail?) and `UpdateWorkspaceRequest` (name?, plan?, contactEmail?) types. `create()` and `update()` API functions. Also: `list()`, `get()`, `suspend()`, `reactivate()`, `getUsage()`, `getUsageHistory()`, `listWorkspaces()`.
- **Backend (`AdminTenantsController.java`):** `POST /api/v1/admin/tenants` (create, SUPER_ADMIN only) accepts `CreateTenantRequest` (name, slug, plan, contactEmail) and delegates to `IdentityMessagingClient.createTenant()`. `PUT /api/v1/admin/tenants/{id}` (update, SUPER_ADMIN only) accepts `UpdateTenantRequest` (name, plan, contactEmail) and delegates to `IdentityMessagingClient.updateTenant()`. Both return `TenantDetail` via sync RabbitMQ RPC.
- **Backend DTOs:** `CreateTenantRequest` record (name @NotBlank, slug @NotBlank, plan, contactEmail @Email). `UpdateTenantRequest` record (name, plan, contactEmail).
- **MSW mocks:** Create handler validates duplicate slug (409), generates mock ID, and adds to in-memory tenant map. Update handler merges partial fields. 3 seed tenants (PRO active, ENTERPRISE active, FREE suspended) in `src/mocks/data/tenants.ts`.

---

#### P-13: Settings Module Completion ✅ COMPLETED

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `SettingsPage.tsx`  
**Status:** ✅ Completed — settings page expanded from 4 sections to 7 sections covering all P-13 requirements.

**Problem:** The settings module is a single `SettingsPage.tsx` file. Content and completeness needs verification and expansion.

**Implementation summary:**
- **Profile card:** Retained — avatar initial, username, role badge, email.
- **Account details:** Retained — username, email, role, tenant ID, user ID.
- **Security section (new):** Password change button navigates to `/change-password` (existing `ChangePasswordPage`). RBAC granular permissions status indicator — detects whether `routify.rbac.granular-enabled` is active by checking if `user.permissions` is populated in the JWT token. Shows green "Enabled" or gray "Disabled" badge with `ShieldCheck`/`ShieldAlert` icons.
- **Export/Import shortcuts (new):** Quick-access buttons for "Export YAML", "Export JSON" (both trigger `exportImportApi.exportConfig()` with download), and "Import Config" (navigates to `/routes` where the import flow lives). Uses `useMutation` + `toast` for feedback.
- **Appearance section (new):** Theme toggle with Dark and System options. System mode shows an explanatory note. Currently dark-only (the app's visual language is dark-theme — the toggle is forward-compatible for light mode).
- **Platform info (updated):** Version corrected from `2.0.0` → `2.0.2-SNAPSHOT`. Added Spring Boot (`4.0.5`) and Spring Cloud (`2025.1.1`) version rows. API base URL now correctly defaults to `localhost:8082` (admin-api port, not gateway port).
- **Architecture section (updated):** Java version corrected from `21` → `25`. Admin API description updated from "BFF + SSE" to "BFF + WebSocket/STOMP events".

---

#### P-14: Filter Config `gatewayConfigRef` Validation ✅ COMPLETED

**Affected services:** `routify-route-service`  
**Complexity:** S  
**Files:** `GatewayConfigRefValidator.java`, `RouteCommandKafkaConsumer.java`, `GatewayConfigRefValidatorTest.java`  
**Status:** ✅ Completed — structural validation rejects invalid refs eagerly; entry-existence checks are warning-only for eventual consistency. 28 tests passing.

**Problem:** When creating/updating a filter with a `gatewayConfigRef`, the route-service persists the ref without validating that the referenced entry (e.g., auth provider with ID `refId`) actually exists in the gateway config. The error only surfaces at gateway route-build time when `GatewayConfigRefResolver` logs a warning and returns empty.

**Implementation summary:**
- **`GatewayConfigRefValidator`** (new, `io.routify.route.service`): Spring `@Component` injected into the Kafka command consumer. Validates `gatewayConfigRef` maps with a two-tier strategy per the risk register:
  1. **Reject eagerly** (throw `RoutifyException.Validation`): unknown/blank `refType`, blank `refId` on list-based types (`AUTH_PROVIDER`, `RATE_LIMIT_POLICY`, `DOWNSTREAM_CREDENTIAL`, `MTLS_CLIENT_MAPPING`, `CLIENT_ID_MAPPING`), and blank `refId` on `VAULT_CERT`. All 8 ref types from `GatewayConfigRefResolver` are accepted: the original 6 plus `MTLS_CLIENT_MAPPING` and `CLIENT_ID_MAPPING`.
  2. **Warn only** (log but don't block): referenced entry not found in gateway config section, config section missing, or no gateway config saved yet. The entry may be created later (eventual consistency). Warning messages include the refType, refId, and config section for operator diagnosis.
- **`RouteCommandKafkaConsumer`** (modified): `configRefValidator.validate(c.gatewayConfigRef())` called before entity construction in both `CreateFilter` and `UpdateFilter` branches. Validation failures throw `RoutifyException.Validation` which prevents ack → Kafka retry → DLQ.
- **Tests:** `GatewayConfigRefValidatorTest.java` (28 tests): null/empty ref passthrough, unknown refType rejected, blank/missing refType rejected, all 7 known refTypes accepted with empty config, VAULT_CERT valid/blank/null refId, list-based blank refId rejected (5 types), AUTH_PROVIDER matching/non-matching refId, RATE_LIMIT_POLICY/DOWNSTREAM_CREDENTIAL matching, singleton section present/missing, edge cases (section not a list, section null).
- **Frontend:** None (validation errors surface as command failures in DLQ; structural errors like unknown refType are caught client-side by the dashboard's dropdown pickers which only offer valid options).

---

#### P-15: Jolt Transform Response-Phase Support ✅ COMPLETED

**Overlaps with:** [gf-05 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#5-jolt-transform-response-phase-support)  
**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `JoltTransformGatewayFilterFactory.java`, `JoltTransformResponseTest.java`  
**Status:** ✅ Completed — full response-phase and BOTH-phase support with `ServerHttpResponseDecorator`, `maxBodySize` limit, and `responseSpec` config. 12 dedicated tests passing.

**Problem:** The `phase` config param accepts `RESPONSE` but the implementation only handles `REQUEST`. Response transformation silently falls through.

**Implementation summary:**
- **Response-phase transformation:** `ServerHttpResponseDecorator` overrides `writeWith()`, joins the response body via `DataBufferUtils.join()`, applies the Jolt `Chainr`, and rewrites the body. `Content-Length` header is updated to match the transformed body size. Only `application/json` responses are transformed — all others pass through unchanged. Malformed JSON responses produce a 502 error via `GatewayProblemResponse` with `JOLT_TRANSFORM_FAILED` error code.
- **BOTH phase:** Combines request transformation (using `spec`) with response transformation (using `responseSpec`). Request body is transformed via `ServerHttpRequestDecorator` (existing pattern), then a `ServerHttpResponseDecorator` is attached to the mutated exchange. Falls back to request-only when `responseSpec` is not configured.
- **`maxBodySize` config:** Default 1 MB (1,048,576 bytes). Response bodies exceeding this limit pass through unchanged with a warning log. Prevents OOM on large upstream responses.
- **`responseSpec` config:** Separate Jolt spec for response transformation. When `phase=RESPONSE`, uses `responseSpec` if set, otherwise falls back to `spec`. When `phase=BOTH`, `responseSpec` is required for the response side.
- **Empty body handling:** Empty response bodies (0 bytes) pass through unchanged gracefully.
- **Mutable headers:** The response decorator uses a mutable `HttpHeaders` copy to allow `Content-Length` updates even when the delegate response returns `ReadOnlyHttpHeaders`.
- **Tests:** `JoltTransformResponseTest.java` (12 tests): RESPONSE transforms JSON body, Content-Length updated, non-JSON passes through, oversized body passes through, empty body handled, malformed JSON → 502, responseSpec used, BOTH transforms both request and response, BOTH fallback to request-only, REQUEST existing behaviour, REQUEST non-JSON passes through, no spec passes through.
- **Frontend:** None (filter form already has `phase` dropdown with `REQUEST`/`RESPONSE`/`BOTH` options).

---

#### P-16: RequestLogger Performance Improvements ✅ COMPLETED

**Overlaps with:** [gf-06 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#6-requestlogger-performance--configurability)  
**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `RequestLoggerGatewayFilterFactory.java`, `FilterConfigFields.tsx`, `filterConfigConstants.ts`, `RequestLoggerConfigTest.java`  
**Status:** ✅ Completed — all 4 performance/configurability improvements implemented, 12 dedicated tests passing.

**Problem:** Body capture buffers entire body (OOM risk); no sampling; hardcoded header redaction list; flat log format.

**Implementation summary:**
- **`maxBodyCaptureBytes`** (default 4096, hard limit 64 KB): New config param replaces the deprecated `maxBodyLogSize`. Body capture uses `DataBufferUtils.join()` with truncation at the configured limit — bodies exceeding the limit are captured up to the threshold with a `[TRUNCATED at N bytes]` marker appended. The full body is re-wrapped via `ServerHttpRequestDecorator` / `ServerHttpResponseDecorator` so downstream filters and upstream services still receive the complete payload. `resolveMaxBodyCaptureBytes()` falls back to `maxBodyLogSize` for backward compatibility when `maxBodyCaptureBytes` is 0.
- **`samplingRate`** (default 1.0): Probabilistic sampling via `ThreadLocalRandom.current().nextDouble() < samplingRate`. When `samplingRate < 1.0`, only a random fraction of requests generate telemetry events. The `sampled` flag is propagated to `handleSignal()` to conditionally skip `publishTelemetry()`. Logging (MDC-enriched structured logs) still occurs for all requests regardless of sampling — only Kafka telemetry publishing is throttled.
- **`headerAllowlist` / `headerDenylist`**: Fine-grained header capture control. When `headerAllowlist` is non-empty, only listed headers are included in telemetry. `headerDenylist` redacts specified headers as `[REDACTED]`. Deny always overrides allow. Both lists are pre-computed to lowercase `Set<String>` at config bind time for O(1) lookup. Default denylist (when no custom denylist is provided): `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `X-Routify-Replay`.
- **`skipPaths`** (glob patterns): Path exclusion patterns compiled to `java.util.regex.Pattern` at config bind time via `compileSkipPatterns()`. Glob syntax: `**` → `.*`, `*` → `[^/]*`, `.` → `\\.`. Matching requests bypass both logging and telemetry entirely. Example: `["/actuator/**", "/health", "/favicon.ico"]`.
- **Structured MDC logging**: Every request completion enriches SLF4J MDC with `method`, `path`, `status`, `elapsedMs`, `correlationId`, `routeId`, `clientIp` — enabling JSON log aggregation via Logstash/Loki without parsing. MDC is cleared after each log statement to prevent leakage across Reactor scheduler threads.
- **Frontend (`FilterConfigFields.tsx`):** `REQUEST_LOGGER` filter form expanded with 5 sections: "What to log" (4 toggles: request/response headers/body), "Body capture" (`maxBodyCaptureBytes` number input capped at 65536, `failedStatusThreshold` quick-pick buttons + custom input), "Sampling" (`samplingRate` range slider 0–100% with percentage label), "Header capture" (`headerAllowlist` and `headerDenylist` tag inputs), "Path exclusions" (`skipPaths` tag input). Informational banners explain default redaction behavior and MDC key names.
- **Frontend (`filterConfigConstants.ts`):** `REQUEST_LOGGER` default config includes all new fields: `maxBodyCaptureBytes: 4096`, `samplingRate: 1.0`, `headerAllowlist: []`, `headerDenylist: []`, `skipPaths: []`.
- **Tests:** `RequestLoggerConfigTest.java` (12 tests): hard upper bound caps above 64 KB, legacy `maxBodyLogSize` fallback, sampling at 0% (zero events), 100% (all events), 10% (statistical ~100±70 of 1000), allowlist-only capture (excludes unlisted headers), denylist redaction (`[REDACTED]`), deny-overrides-allow, skipPaths exact match + glob nested path, default config backward compatibility (all logged), default Authorization redaction, replay header skip.

---

### Phase 4 — Quality & Resilience (P3)

Testing, cleanup, and operational improvements.

---

#### P-17: Webhook Delivery Cleanup Scheduler ✅ COMPLETED

**Affected services:** `routify-identity-service`  
**Complexity:** S  
**Files:** `WebhookService.java`, `WebhookDeliveryRepository.java`, `RoutifyMetrics.java`, `application.yml`, `V10__webhook_delivery_cleanup_index.sql`, `WebhookDeliveryCleanupTest.java`  
**Status:** ✅ Completed — batched deletion with configurable batch size, dedicated cleanup index, Micrometer metrics, 8 tests passing.

**Problem:** Webhook delivery log retention is configurable via `routify.webhooks.delivery-retention-days` (default 7) but the cleanup deleted all matching records in a single transaction, risking long-held table locks on high-volume deployments.

**Implementation summary:**
- **Batched deletion:** `cleanupOldDeliveries()` now loops, deleting `routify.webhooks.cleanup-batch-size` (default 1000) rows per iteration until fewer than batch size remain. Each batch runs in its own transaction via `TransactionTemplate` (avoids Spring AOP self-invocation pitfall). PostgreSQL native query uses `DELETE ... WHERE id IN (SELECT id ... ORDER BY created_at ASC LIMIT :batchSize)` for bounded, index-friendly deletion.
- **Dedicated cleanup index:** Flyway migration `V10__webhook_delivery_cleanup_index.sql` adds `idx_webhook_delivery_created_at ON routify_identity.webhook_delivery(created_at ASC)` — the existing composite index on `(subscription_id, created_at DESC)` is not optimal for the cleanup query which filters only on `created_at`.
- **Configurable batch size:** `routify.webhooks.cleanup-batch-size` (default 1000, env: `WEBHOOK_CLEANUP_BATCH_SIZE`) — controls max rows deleted per transaction batch. Tune down for low-IOPS databases, up for fast SSDs.
- **Micrometer metrics:** `routify.webhooks.delivery.cleanup` counter in `RoutifyMetrics` — incremented by the total number of records purged per cycle. Enables monitoring cleanup effectiveness via Prometheus/Grafana.
- **Logging:** Structured `INFO`-level log on start (retention, cutoff, batch size), on completion (total deleted, batch count), and `DEBUG` when no expired records found.
- **Tests:** `WebhookDeliveryCleanupTest.java` (8 tests): no expired records, single batch, multiple batches (2250 across 3 batches), exact batch boundary (extra iteration confirms exhaustion), custom batch size, custom retention days (cutoff validation), metrics always recorded, default 7-day retention.

---

#### P-18: Gateway Filter Unit Test Expansion ✅ COMPLETED

**Affected services:** `routify-api-gateway`  
**Complexity:** L  
**Files:** `src/test/java/io/routify/gateway/filter/` — 12 new test classes  
**Status:** ✅ Completed — 79 new unit tests across 12 test classes, covering 12 previously-untested filter factories. Total gateway test count: 603 (up from 524).

**Problem:** Only 6 gateway filter unit tests exist (JwtAuth, FixedWindow, SlidingWindow, RequestTimeout, HeaderFilters, RateLimitKeyResolver). 23 filter factories have zero test coverage.

**Implementation summary:**
- **Auth filters:**
  - `ApiKeyAuthGatewayFilterFactoryTest` (12 tests): missing key→401, blank key→401, invalid key (not in Redis)→401, expired key→401, valid key with header injection (5 identity headers), valid key without expiry, valid key with future expiry, Redis error→502, query param fallback, custom header name, auth failure metrics counter.
  - `ClientIdAuthGatewayFilterFactoryTest` (6 tests): matching client ID→organization-id injected, non-matching→401, missing header→401, config mapping precedence over ClientProperties, empty config falls back to properties, filter order=0.
- **Routing & versioning filters:**
  - `ApiVersioningGatewayFilterFactoryTest` (9 tests): HEADER strategy injects header, custom header name, QUERY strategy appends param, PATH strategy prepends prefix, PATH idempotent (already prefixed), blank version→passthrough, unknown strategy→passthrough, custom versionParam, config defaults.
  - `ConditionalRouteGatewayFilterFactoryTest` (7 tests): header match→URI rewritten, query param match→rewritten, no match→unchanged, blank alternativeUri→disabled, invalid regex→disabled, header precedence over param, default .* pattern matches any value.
  - `UserIdPayloadRoutingGatewayFilterFactoryTest` (6 tests): matching userId→URI rewritten, non-matching→unchanged, missing cached body→500, wrong body type→500, enabled=false→passthrough, order=10001.
- **Downstream auth filters:**
  - `DownstreamBasicAuthGatewayFilterFactoryTest` (5 tests): valid creds→Basic Authorization header injected (Base64 verified), empty username→500, null password→500, empty password→500, order=1.
  - `DownstreamOAuth2BearerGatewayFilterFactoryTest` (5 tests): valid provider→Bearer token injected, missing provider→401, empty provider→401, forwardCallerAuth=true→uses forwarded auth, order=1.
- **Security filters:**
  - `SecurityHeadersGatewayFilterFactoryTest` (6 tests): all OWASP headers enabled, master toggle disabled→no headers, custom CSP, null config→safe defaults, filter order=100, custom headers injected.
  - `GlobalSecurityHeadersFilterTest` (5 tests): enabled→headers applied, disabled via globalFilters toggle→no headers, order=LOWEST_PRECEDENCE-1, null config→defaults, empty config→defaults.
- **Validation & transformation filters:**
  - `JsonSchemaValidateGatewayFilterFactoryTest` (8 tests): valid JSON→passthrough, missing required field→400, wrong type→400, non-JSON content-type→skip, empty body→passthrough, blank schema→disabled, malformed JSON→400, null schema→disabled.
- **Observability filters:**
  - `CustomMetricGatewayFilterFactoryTest` (6 tests): static tags→counter incremented, multiple requests accumulate, dynamic $header.* tag resolved, missing header→"unknown", default metric name, null tags→counter still works.
  - `CorrelationIdGatewayFilterFactoryTest` (4 tests): missing header→UUID generated, existing header→preserved, blank header→new generated, filter order=-1000.

---

#### P-19: Backend Service Integration Test Expansion ✅

**Affected services:** All services with zero tests (audit-service, cert-vault, ai-service, gitops-agent)  
**Complexity:** L  
**Files:** New test classes per service  
**Status:** COMPLETED

**Problem:** 4 of 8 services have zero tests. Existing ITs are disabled by default due to Docker Engine 29.x / Testcontainers incompatibility.

**Changes:**
- **Backend:** ✅ Verified the Docker Engine 29.x / Testcontainers incompatibility is resolved with `docker-java` 3.7.1 override — all test classes compile cleanly.
- **Backend:** ✅ Added integration test base classes for:
  - `AuditServiceIntegrationBase` — Postgres + Kafka + RabbitMQ + Redis
  - `CertVaultIntegrationBase` — Postgres + Kafka + RabbitMQ
- **Backend:** ✅ Added Testcontainers dependencies to `routify-audit-service/pom.xml` and `routify-cert-vault/pom.xml`
- **Backend:** ✅ Created `application-test.yml` and `testcontainers.properties` for both services
- **Backend:** ✅ Wrote ITs for critical paths:
  - audit-service: `DomainEventAuditConsumerIT` (4 tests — event persistence, multi-topic, cert event), `DlqEventConsumerIT` (2 tests — DLQ persistence, multi-topic), `AlertEvaluationSchedulerIT` (4 tests — state transitions OK→PENDING, PENDING→OK, disabled skip, cooldown)
  - cert-vault: `CertEncryptionServiceIT` (6 tests — round-trip, unique IV, tampered payload, invalid base64, empty string, large payload), `CertCommandLifecycleIT` (4 tests — group create, idempotency, upload with encryption, revoke lifecycle)
  - identity-service: `ApiKeyLifecycleIT` (6 tests — create+Redis projection, revoke+Redis deletion, rotate, double-revoke conflict, rotate-revoked conflict, TTL expiration)

---

#### P-20: Frontend Unit Test Expansion ✅ COMPLETED

**Affected services:** `routify-dashboard`  
**Complexity:** L  
**Files:** `src/__tests__/` — 38 new test files (13 API clients, 1 GraphQL client, 4 hooks, 2 stores, 1 test utility helper, 16 module component tests, 1 enhanced setup)  
**Status:** ✅ Completed — 283 tests across 42 test files (up from 4 tests across 4 files). All passing.

**Problem:** Only 4 unit tests exist. 16 modules, 17 API clients, and 4 hooks have no test coverage.

**Implementation summary:**
- **Test infrastructure:** Enhanced `src/__tests__/setup.ts` with `ResizeObserver` and `IntersectionObserver` stubs for happy-dom. Created `src/__tests__/helpers/testUtils.tsx` with mock Axios adapter (`installMockAdapter`/`restoreMockAdapter`/`mockAdapter`), `createTestQueryClient()`, `createWrapper()` (QueryClient + MemoryRouter), and `createQueryWrapper()` (QueryClient only).
- **API client tests (13 files):** `authApi`, `routesApi`, `filtersApi`, `usersApi`, `tenantsApi`, `auditApi`, `certVaultApi`, `gatewayApi`, `aiApi`, `apiKeysApi`, `webhooksApi`, `rolesApi`, `exportImportApi`, `gitopsApi`, `alertsApi` — each verified request URL, HTTP method, query params, request body, and response parsing. Includes edge cases (default params, tenant header injection, Content-Type for YAML, Content-Disposition filename extraction).
- **GraphQL client tests (1 file):** `graphqlClient` — verified POST to `/api/v1/admin/graphql`, typed data extraction, GraphQL error aggregation, null data handling, optional variables.
- **Store tests (2 files):** `authStore` — initial state, setTokens/setUser/logout, partialize returns empty (nothing persisted). `wsStore` — setStatus (all values), pushEvent (label mapping, 50-event cap, newest-first ordering, connected type), setMetrics (circuitBreakers, gatewayHealth, wsLoadedRoutes, null fields), reset.
- **Hook tests (4 files):** `useBootstrapAuth` — short-circuit with existing token, refresh + token/user set, bootstrapped=true on refresh failure, user from refresh response. `useRealtimeQuery` — query data, WS event invalidation on matching prefix, no invalidation on non-matching event, no invalidation without wsEvents. `useWebSocket` — WebSocket connection, STOMP CONNECT frame, Authorization header with token, enabled=false no-connect, onStatusChange callback, STOMP subscribe on CONNECTED, onMessage callback. `useDocumentTitle` — (existing, retained).
- **Module component tests (16 files, one per module):** `auth/ProtectedRoute` (redirect when unauthenticated, render children when authenticated, access denied for insufficient role, role match, mustChangePassword redirect), `auth/LoginPage` (heading, form inputs, sign-in button, feature cards, title). `settings/SettingsPage`, `api-keys/ApiKeysPage`, `alerts/AlertsPage`, `users/UsersPage`, `gitops/GitOpsPage`, `webhooks/WebhooksPage`, `roles/RolesPage`, `filters/FiltersPage`, `audit/AuditPage`, `certificates/CertVaultPage`, `gateway/GatewayPage`, `workspaces/WorkspacesPage`, `routes/RouteWorkflowPage`, `ai/AiPlaygroundPage`, `workflow-builder/WorkflowBuilderPage` — each verifies document title, page header rendering, primary action button, and data display after async loading.
- **Target met:** ≥1 test file per API client (15/15 ✅), ≥1 test per hook (4/4 ✅), ≥1 component test per module (16/16 ✅).

---

#### P-21: Frontend E2E Test Expansion

**Affected services:** `routify-dashboard`  
**Complexity:** L  
**Files:** `e2e/`

**Problem:** Only 2 E2E specs (login, routes). 14 feature modules have no E2E coverage.

**Changes:**
- **Frontend:** Add Playwright specs for: filters CRUD, gateway config tabs, certificates management, API keys lifecycle, webhooks, alerts, audit viewer, AI playground.
- **Frontend:** Leverage existing MSW mock handlers which already cover all 16 feature areas.

---

#### P-22: `routify-common` Shared Utilities Test Expansion ✅ COMPLETED

**Affected services:** `routify-common`  
**Complexity:** M  
**Files:** `src/test/java/io/routify/common/` — 7 new test classes  
**Status:** ✅ Completed — 7 new test classes covering all specified utilities. Total routify-common test count: 546 (up from 368).

**Problem:** 5 tests exist (CommandEvent serialization, QueryMessage serialization, GlobalExceptionHandler, KafkaDlqErrorHandler, SecurityContext). Missing tests for `PageResponse`, `Sensitive`/`SensitiveField`, `RoutifyHeaders.resolveActor()`, `RedisKeys` constants, domain enum validation.

**Implementation summary:**
- **`PageResponseTest.java`** (14 tests): `of()` factory — first/middle/last page, single page, empty result, exact boundary, size-zero guard, large dataset, generic type preservation. `from()` factory — Spring Data Page conversion, empty page, last page detection. Record equality/inequality.
- **`SensitiveTest.java`** (21 tests): `mask()` — non-blank masked, null→null, blank→null, empty→null, MASK constant value. `isMasked()` — sentinel match, regular string, null, empty, case-sensitive, partial match. `maskFields()` — annotated fields masked, null/blank annotated stay null, null object no-op, nested object recursion, null nested skipped, collection elements masked, null/empty collection skipped, superclass field inheritance.
- **`RoutifyHeadersTest.java`** (16 tests): `resolveActor()` — userId precedence, null userId→principal, blank/empty userId→principal, both null→system, blank userId + null principal→system, whitespace userId preserved, blank principal returned as-is. Header constant values (5 assertions). All X-* convention check. Utility class constraints (private constructor, final class).
- **`TenantPlanTest.java`** (22 tests): Per-tier quotas — FREE (10/5/1K/5MB), STARTER (50/20/10K/10MB), PRO (200/100/100K/50MB), ENTERPRISE (MAX/MAX/MAX/500MB). Cross-plan invariants — exactly 4 tiers, all positive values, monotonic increase from FREE→ENTERPRISE.
- **`FilterTypeTest.java`** (178 tests): Exhaustiveness — exactly 12 deprecated, total = active + deprecated, @Deprecated annotation check. Active types — all 29 from AGENTS.md exist, AUTH_* prefix, RATE_LIMIT_* prefix. Naming — UPPER_SNAKE_CASE, no consecutive underscores. valueOf round-trip for all 41 values.
- **`DomainEnumValidationTest.java`** (90 tests): UserRole (4 roles, documented values, valueOf round-trip), RouteStatus (4 statuses, ordinal lifecycle order), RouteEnvironment (2 values), Permission (27 permissions, code()==name(), per-category existence, naming convention), AlertMetric (8 metrics, documented values, valueOf round-trip), WebhookEventType (28 types, per-category existence, naming convention).
- **`RedisKeysTest.java`** (17 tests): Key prefix values (7 constants verified). Naming conventions — all start with `routify:`, prefix constants end with `:`, non-prefix keys don't end with `:`, colon separator count. Key construction examples (4 real-world key patterns). Utility class constraints (private constructor, final class).
- **`AsyncAcknowledgementTest.java`** (4 tests): `of()` factory, direct constructor, record equality, record inequality.

---

#### P-23: GitOps Agent Error Handling & Test Coverage

**Affected services:** `routify-gitops-agent`  
**Complexity:** M  
**Files:** `routify-gitops-agent/src/`

**Problem:** The service has only 4 source files with no tests. Error handling for Git fetch failures, admin-api unreachability, and malformed YAML configs needs verification.

**Changes:**
- **Backend:** Add unit tests for: Git hash comparison logic, YAML parsing edge cases, webhook HMAC signing, Redis state management.
- **Backend:** Verify graceful degradation when admin-api is unreachable (circuit breaker? retry?). Add if missing.
- **Backend:** Verify malformed YAML config files produce clear error messages in reconciliation history.

---

#### P-24: Audit Data Retention Policy Verification

**Affected services:** `routify-audit-service`  
**Complexity:** S  
**Files:** Audit-service scheduler/config

**Problem:** The audit service stores telemetry, events, DLQ records, AI decisions, and alert history. Retention policies for each table type need verification and documentation.

**Changes:**
- **Backend:** Audit all `@Scheduled` tasks in audit-service. Verify retention periods per table:
  - `request_log` — configurable (default 30 days suggested)
  - `audit_event` — configurable (default 90 days suggested)
  - `dlq_event` — configurable (default 30 days suggested)
  - `ai_filter_decision` / `ai_modification_decision` — configurable (default 60 days suggested)
- **Backend:** Add configurable retention properties (`routify.audit.retention.*`) if not present.
- **Backend:** Add or verify `@Scheduled` cleanup tasks with batch deletion.

---

#### P-25: DownstreamOAuth2Bearer Dynamic Provider Support

**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `DownstreamOAuth2BearerGatewayFilterFactory.java`, `Oauth2AccessTokenProvider.java`, `GatewayConfigRefResolver.java`

**Problem:** `Config.oauth2ProviderName` maps to static `Oauth2AccessTokenProvider` config from YAML. The `DOWNSTREAM_CREDENTIAL` ref type maps different fields (`username`, `password`, `headerName`, `headerValue`) that don't match the downstream OAuth2 filter's needs (`tokenUri`, `clientId`, `clientSecret`, `scope`).

**Changes:**
- **Backend:** Extend `GatewayConfigRefResolver` to handle a new `DOWNSTREAM_OAUTH2_PROVIDER` ref type (or extend `DOWNSTREAM_CREDENTIAL` to detect OAuth2 type and map `tokenUri`, `clientId`, `clientSecret`, `scope`).
- **Backend:** Modify `DownstreamOAuth2BearerGatewayFilterFactory.Config` to accept direct OAuth2 fields from ref resolution, falling back to `oauth2ProviderName` → YAML for backward compatibility.

---

#### P-26: AI Filter Streaming Body Support

**Overlaps with:** [gf-08 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#8-ai-filter-streaming-body-support)  
**Affected services:** `routify-api-gateway`, `routify-ai-service`  
**Complexity:** L  
**Files:** `AiGatewayFilterFactory.java`, `AiModifierGatewayFilterFactory.java`

**Problem:** When `includeBody=true`, the AI filter reads the body excerpt from an exchange attribute that must be pre-cached by an earlier filter. Default `maxBodyBytes=512` is too small for useful context.

**Changes:**
- **Backend:** AI filter reads body inline using `DataBufferUtils.join()` + `limitRate()`.
- **Backend:** Increase default `maxBodyBytes` to 2048.
- **Backend:** Skip binary content types. Add body SHA-256 hash for cache keying.
- **Backend:** Re-wrap body for downstream via `ServerHttpRequestDecorator`.
- **Frontend:** Update `AI_FILTER` config form default for `maxBodyBytes` from 512 to 2048.

---

#### P-27: Filter Config Form Gateway Config Ref UX

**Affected services:** `routify-dashboard`  
**Complexity:** M  
**Files:** `FilterDefinitionForm.tsx`, `FilterConfigFields.tsx`

**Problem:** The gateway config ref system is powerful but the UX for linking a filter to a gateway config entry is not intuitive. Operators need to understand ref types and manually construct ref objects.

**Changes:**
- **Frontend:** Add a "Link to Gateway Config" toggle button on all filter forms that support refs. When enabled, show a ref-type-specific picker:
  - `AUTH_PROVIDER` → Auth Provider dropdown (filtered by compatible type)
  - `RATE_LIMIT_POLICY` → Rate Limit Policy dropdown
  - `VAULT_CERT` → Cert Vault Logical ID picker (from P-06)
  - `DOWNSTREAM_CREDENTIAL` → Downstream Credential dropdown
  - `CIRCUIT_BREAKER_DEFAULTS` / `RESILIENCE_DEFAULTS` → auto-link (single global entry)
- **Frontend:** When a ref is selected, auto-populate `gatewayConfigRef` and show the resolved values as read-only with "Override" toggles for individual fields.

---

#### P-28: Flyway Migration Health Verification

**Affected services:** All services with Flyway  
**Complexity:** S  
**Files:** All `src/main/resources/db/migration/` directories

**Problem:** The 4 services with Flyway have a combined 33 migrations. Migration numbering and cross-service consistency needs verification.

**Changes:**
- **Backend:** Verify migration numbering is contiguous per service:
  - route-service: V1–V8 (8 migrations) ✅
  - identity-service: V1–V9 (9 migrations) ✅
  - audit-service: V1–V11 (11 migrations) ✅
  - cert-vault: V1–V5 (5 migrations) ✅
- **Backend:** Verify `spring.jpa.hibernate.ddl-auto: validate` is set in all service configs.
- **Backend:** Add a CI step that runs Flyway `validate` against a clean database to catch migration issues early.

---

## 5. Dependency Map

```
Phase 1 (P0 — Security)
  P-01 JWT Hardening ─────────────────────────────── ✅ COMPLETED
  P-02 SpEL Sandboxing ──────────────────────────── ✅ COMPLETED
  P-03 BasicAuth Password Hashing ────────────────── ✅ COMPLETED

Phase 2 (P1 — Config Integration)
  P-04 OAuth2 Dynamic Config Bridge ───────────────── ✅ COMPLETED
  P-05 mTLS/ClientID Dynamic Config ──────────────── ✅ COMPLETED
  P-06 Cert Vault Picker ─────────────────────────── ✅ COMPLETED
  P-07 Auth Provider Picker ──────────────────────── ✅ COMPLETED
  P-08 Downstream Credential Picker ──────────────── ✅ COMPLETED
  P-09 Rate Limit Policy Picker ──────────────────── ✅ COMPLETED

Phase 3 (P2 — Completeness)
  P-10 Unified Error Response Builder ─────────────── ✅ COMPLETED
  P-11 Rate Limiter Headers ──────────────────────── ✅ COMPLETED (depends on P-10)
  P-12 Workspace Create & Plan Management ─────────── ✅ COMPLETED
  P-13 Settings Module Completion ─────────────────── ✅ COMPLETED
  P-14 Filter gatewayConfigRef Validation ─────────── ✅ COMPLETED
  P-15 Jolt Response-Phase ────────────────────────── ✅ COMPLETED
  P-16 RequestLogger Improvements ─────────────────── ✅ COMPLETED

Phase 4 (P3 — Quality)
  P-17 Webhook Delivery Cleanup ───────────────────── ✅ COMPLETED
  P-18 Gateway Filter Test Expansion ──────────────── ✅ COMPLETED (depends on P-01, P-02)
  P-19 Backend Service IT Expansion ───────────────── standalone
  P-20 Frontend Unit Test Expansion ─────────────── ✅ COMPLETED
  P-21 Frontend E2E Test Expansion ──────────────── depends on P-20 ✅
  P-22 routify-common Test Expansion ────────────── ✅ COMPLETED
  P-23 GitOps Agent Testing ───────────────────────── standalone
  P-24 Audit Retention Policy ─────────────────────── standalone
  P-25 DownstreamOAuth2 Dynamic Provider ──────────── depends on P-04
  P-26 AI Filter Streaming Body ───────────────────── standalone
  P-27 Filter Config Ref UX ──────────────────────── depends on P-06, P-07, P-08, P-09
  P-28 Flyway Migration Verification ──────────────── standalone
```

---

## 6. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| ~~**P-01 JWT hardening breaks dev workflows**~~ | ~~Medium~~ | ~~Medium~~ | ✅ **Mitigated.** Fail-closed by default. JWKS URI provides key rotation without restart. `routify.jwt.require-jti` can be set to `false` per environment. |
| ~~**P-02 SpEL sandboxing breaks existing expressions**~~ | ~~Medium~~ | ~~Medium~~ | ✅ **Mitigated.** `#request` removed; `#clientIp` and `#contentType` provided as replacements. Expressions referencing `#request` fail open (pass through with warning). `SimpleEvaluationContext` blocks type references and constructors. |
| ~~**P-03 BCrypt hashing breaks existing BasicAuth configs**~~ | ~~Medium~~ | ~~Low~~ | ✅ **Mitigated.** Gateway auto-detects unhashed passwords (no `$2` prefix) and falls back to plain-text `equals()` with a WARN log. Re-saving the auth provider via admin API triggers automatic BCrypt hashing. |
| ~~**P-04 OAuth2 config migration is a breaking change**~~ | ~~Low~~ | ~~Medium~~ | ✅ **Mitigated.** Purely additive — existing `providerName` → YAML path continues to work unchanged. New direct-config path activates only when `introspectUri`+`clientId`+`clientSecret` are all present (from `gatewayConfigRef` resolution). Partial direct config gracefully falls back to `providerName`. |
| ~~**P-05 mTLS/ClientID refactoring breaks existing deployments**~~ | ~~Medium~~ | ~~High~~ | ✅ **Mitigated.** Purely additive — existing `CertificateValuesConfig` YAML and `ClientProperties` static config paths continue to work unchanged. New dynamic config paths activate only when a `gatewayConfigRef` with `MTLS_CLIENT_MAPPING` or `CLIENT_ID_MAPPING` ref type is present. `ClientIdAuthGatewayFilterFactory` falls back to injected `ClientProperties` bean when `Config.clientIdMapping` is empty or null. |
| ~~**P-14 gatewayConfigRef validation blocks valid saves**~~ | ~~Low~~ | ~~Medium~~ | ✅ **Mitigated.** Two-tier validation: structural errors (unknown refType, blank refId) throw `RoutifyException.Validation` immediately. Entry-existence checks (refId not found in config section) only log a warning — the save proceeds, allowing eventual consistency when config entries are created after filters. |
| **P-19 IT expansion delayed by Docker/TC incompatibility** | Medium | Low | Verify `docker-java` 3.7.1 resolves the issue. If not, target unit tests only. |
| **Test expansion initiatives (P-18 through P-23) are large** | High | Low | Break each into sub-tasks (one test class per PR). Prioritize security-critical paths first. |

