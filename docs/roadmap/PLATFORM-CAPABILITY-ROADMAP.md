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
4. **Test coverage** — expanding from 19 Java tests and 6 frontend tests to meaningful coverage (4 initiatives)
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
| JWT issuer/audience validation | ❌ | Config params declared but **never validated** — see [P-01](#p-01-jwt-auth-filter-hardening) |
| OAuth2 dynamic config bridge | ❌ | `OAuth2TokenIntrospectGatewayFilterFactory` reads `AuthProperties` YAML, ignores resolved `gatewayConfigRef` values — see [P-04](#p-04-oauth2-auth-provider-dynamic-config-bridge) |
| mTLS/ClientID dynamic config | ❌ | Use legacy static `CertificateValuesConfig`/`ClientProperties` — see [P-05](#p-05-mtlsclientid-migration-to-dynamic-gateway-config) |
| SpEL sandboxing | ❌ | `StandardEvaluationContext` exposes `#request` → ClassLoader escape — see [P-02](#p-02-spel-filter-sandboxing) |

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
| `gatewayConfigRef` validation on filter save | ❌ | No validation that `refType`/`refId` references a real gateway config entry — see [P-14](#p-14-filter-config-gatewayconfigref-validation) |

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
| Webhook delivery cleanup | ⚠️ | Retention configurable but no active scheduler found — see [P-17](#p-17-webhook-delivery-cleanup-scheduler) |

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
| Filters | Full | ✅ | Missing cert vault picker, OAuth2 provider picker |
| Gateway Config | 13 tabs | ✅ | — |
| Certificates | Full | ✅ | — |
| API Keys | Full | ✅ | — |
| Webhooks | Full | ✅ | — |
| Users | Full | ✅ | — |
| Workspaces | Read + usage | ⚠️ | Missing create workspace flow, plan management |
| Roles | Full | ✅ | — |
| Audit | Read + replay | ✅ | — |
| AI | Full | ✅ | Playground, versions, comparison |
| Alerts | Full | ✅ | — |
| GitOps | Status + sync | ✅ | — |
| Settings | Minimal | ⚠️ | Single `SettingsPage.tsx` — content needs verification |

---

## 3. Identified Issues & Gaps

### 3a. Gateway Filter Integration Gaps

| # | Filter | Issue | Severity |
|---|--------|-------|----------|
| 1 | `JwtAuthGatewayFilterFactory` | `Config.issuer` and `Config.audience` are declared but **never validated** in `parseAndValidate()`. The `issuer` resolved from `gatewayConfigRef` (`issuerUri`) lands in the config but is never checked against `claims.getIssuer()`. | **High** |
| 2 | `JwtAuthGatewayFilterFactory` | Dev-mode unsigned JWT fallback: when `routify.jwt.public-key` is empty, the filter decodes JWT payload **without signature verification**. If this config is accidentally unset in production, all JWTs are accepted regardless of signature. | **High** |
| 3 | `JwtAuthGatewayFilterFactory` | `Config.algorithm` supports `HS256` in the config schema but the `parseAndValidate()` implementation only handles RS256 (public key). HS256 secret-key parsing is not implemented. | **Low** |
| 4 | `SpelCustomGatewayFilterFactory` | Uses `StandardEvaluationContext` which exposes `#request` (full `ServerHttpRequest`). A malicious operator can call `#request.getClass().getClassLoader()` to escape the sandbox and execute arbitrary code. | **High** |
| 5 | `OAuth2TokenIntrospectGatewayFilterFactory` | Reads `providerName` from `Config`, then looks it up in `AuthProperties.oauth2Verification` (static YAML map). The `GatewayConfigRefResolver.resolveAuthProvider()` correctly resolves `introspectUri`/`clientId`/`clientSecret` from gateway config, but these land as config keys the filter **ignores** — it only reads `providerName` and delegates to `AuthProperties`. The bridge is broken. | **Medium** |
| 6 | `MtlsAuthGatewayFilterFactory` | Config class is `CertificateValuesConfig` (from `auth.properties` YAML). No `GatewayConfigRefResolver` mapping exists for `AUTH_MTLS`. Filter cannot import cert mappings from dynamic gateway config. | **Medium** |
| 7 | `ClientIdAuthGatewayFilterFactory` | Config class is `NameValuesConfig` backed by `ClientProperties` YAML. No `GatewayConfigRefResolver` mapping for `AUTH_CLIENT_ID`. Same static-config limitation as mTLS. | **Medium** |
| 8 | `DownstreamOAuth2BearerGatewayFilterFactory` | `Config.oauth2ProviderName` maps to `Oauth2AccessTokenProvider` static config. Not integrated with the `DOWNSTREAM_CREDENTIAL` ref type or dynamic auth provider config. | **Medium** |
| 9 | `BasicAuthGatewayFilterFactory` | Password comparison is plain-text `equals()`. While `@SensitiveField` masks the password in API responses, the stored value in gateway config is not hashed. | **Low** |
| 10 | Cert-related filters (`AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`) | `VAULT_CERT` ref type correctly injects `logicalId`, but the dashboard filter form requires manual `logicalId` text entry. No cert picker dropdown populated from cert-vault. | **Medium** |

### 3b. Cross-Service Config Resolution Gaps

| # | Gap | Details |
|---|-----|---------|
| 1 | **OAuth2 provider config disconnect** | Auth providers defined in gateway config (`authProviders` section, type `OAUTH2_*`) cannot be consumed by `OAuth2TokenIntrospectGatewayFilterFactory` because the filter reads from `AuthProperties` YAML, not from the resolved `gatewayConfigRef` values. An operator who configures an OAuth2 provider in the gateway config UI and links it to an `AUTH_OAUTH2` filter will get a `"Missing Oauth2 verification configuration"` error at runtime. |
| 2 | **mTLS static config island** | `MtlsAuthGatewayFilterFactory` uses `CertificateValuesConfig` which requires certificate-to-client-ID mappings baked into YAML. These cannot be managed through the dashboard. The `VAULT_CERT` ref type exists but doesn't map to mTLS's `values[].clientCertificateValue` / `values[].clientIdValue` structure. |
| 3 | **Downstream OAuth2 provider disconnect** | `DownstreamOAuth2BearerGatewayFilterFactory.Config.oauth2ProviderName` maps to `Oauth2AccessTokenProvider` YAML config. The `DOWNSTREAM_CREDENTIAL` ref type maps `username`/`password`/`headerName`/`headerValue` but these aren't the fields the downstream OAuth2 filter reads (`oauth2ProviderName`, `forwardCallerAuth`). |
| 4 | **No `gatewayConfigRef` validation on save** | When creating or updating a filter with a `gatewayConfigRef`, the route-service persists the ref without validating that the referenced gateway config entry (e.g., auth provider with that `refId`) actually exists. The error surfaces only at gateway route-build time. |

### 3c. Frontend Dashboard Gaps

| # | Module | Gap | Impact |
|---|--------|-----|--------|
| 1 | Filters | No **cert vault picker** for `AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK` — `logicalId` is a free-text input | Operator must manually type the exact logical ID |
| 2 | Filters | No **OAuth2 auth provider picker** for `AUTH_OAUTH2` — `providerName` is free-text | Operators don't know which providers are configured |
| 3 | Filters | No **downstream credential picker** for `DOWNSTREAM_BASIC_AUTH`, `DOWNSTREAM_BEARER_CC` | Must manually type credential names |
| 4 | Filters | No **rate limit policy picker** for `RATE_LIMIT_FIXED_WINDOW`, `RATE_LIMIT_SLIDING_WINDOW` | Must manually configure values instead of selecting a policy |
| 5 | Workspaces | No **create workspace** action — `WorkspacesPage.tsx` shows usage only | Cannot create tenants from the dashboard |
| 6 | Workspaces | No **plan upgrade/change** flow | Plan changes require direct API/DB access |
| 7 | Settings | `SettingsPage.tsx` is a single file — may be a stub | Settings module may be incomplete |

### 3d. Testing Gaps

| Area | Current | Total Surface | Coverage |
|------|---------|--------------|----------|
| **Java unit tests** | 11 tests across 3 services (gateway: 6, common: 5) | ~300+ source files across 8 services | ~4% |
| **Java integration tests** | 5 ITs across 3 services (admin-api: 2, identity: 2, route: 1) | 8 services with DB/messaging | ~3% |
| **Frontend unit tests** | 4 tests (client, ErrorBoundary, useDocumentTitle, utils) | 16 modules + 17 API clients + 4 hooks + 2 stores | ~5% |
| **Frontend E2E tests** | 2 specs (login, routes) | 16 feature modules | ~12% |
| **Services with zero tests** | audit-service, cert-vault, ai-service, gitops-agent | — | 0% |

---

## 4. Improvement Initiatives

### Phase 1 — Security & Correctness (P0)

Critical issues that could lead to security vulnerabilities or incorrect behavior in production.

---

#### P-01: JWT Auth Filter Hardening

**Overlaps with:** [gf-04 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#4-jwtauth-filter-hardening)  
**Affected services:** `routify-api-gateway`  
**Complexity:** S  
**Files:** `JwtAuthGatewayFilterFactory.java`

**Problem:**
1. `Config.issuer` and `Config.audience` are declared but the `parseAndValidate()` method never checks `claims.getIssuer()` or `claims.getAudience()` against them.
2. When `routify.jwt.public-key` is blank, the filter decodes JWTs without signature verification (dev-mode fallback). If this accidentally reaches production, any crafted JWT is accepted.
3. Tokens without a `jti` claim skip the Redis blocklist check entirely (only a warning is logged).

**Changes:**
- **Backend:** In `parseAndValidate()`, after parsing claims:
  - If `config.getIssuer()` is non-blank, validate `claims.getIssuer().equals(config.getIssuer())`.
  - If `config.getAudience()` is non-blank, validate `claims.getAudience().contains(config.getAudience())`.
  - Throw `SecurityException` on mismatch.
- **Backend:** Replace the dev-mode unsigned decode path with a fail-closed error: if no public key is configured, reject all JWT requests with `SERVER_MISCONFIGURED`. Log a startup `WARN`.
- **Backend:** Add `requireJti` config flag (default `true`). When true, reject tokens without a `jti` claim instead of skipping the blocklist check.
- **Frontend:** None.

---

#### P-02: SpEL Filter Sandboxing

**Overlaps with:** [gf-07 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#7-spel-filter-sandboxing--security)  
**Affected services:** `routify-api-gateway`  
**Complexity:** S  
**Files:** `SpelCustomGatewayFilterFactory.java`

**Problem:** `StandardEvaluationContext` exposes `#request` (full `ServerHttpRequest` object), enabling `#request.getClass().getClassLoader()` → arbitrary code execution.

**Changes:**
- **Backend:** Replace `StandardEvaluationContext` with `SimpleEvaluationContext.forReadOnlyDataBinding()`.
- **Backend:** Remove `#request` variable. Keep `#headers`, `#params`, `#method`, `#path` (all immutable strings/maps). Add `#clientIp`, `#contentType`.
- **Backend:** Add expression length limit (500 chars, configurable).
- **Frontend:** None.

---

#### P-03: BasicAuth Password Hashing in Gateway Config

**Affected services:** `routify-api-gateway`, `routify-admin-api`, `routify-route-service`  
**Complexity:** S  
**Files:** `BasicAuthGatewayFilterFactory.java`, `GatewayConfigController.java`

**Problem:** `BasicAuthGatewayFilterFactory` compares passwords using plain-text `equals()`. The password stored in gateway config (via the Auth Providers section) is stored unencrypted. `@SensitiveField` masks it in API responses but not at rest.

**Changes:**
- **Backend (admin-api/route-service):** When saving an auth provider of type `BASIC`, BCrypt-hash the password before persisting. Use `Sensitive.isMasked(value)` to skip re-hashing on updates where the password field is masked.
- **Backend (gateway):** In `BasicAuthGatewayFilterFactory.apply()`, use `BCryptPasswordEncoder.matches()` instead of `equals()` for password comparison. The filter config `password` field now contains a BCrypt hash.
- **Frontend:** None (password field already masked by `@SensitiveField`).

---

### Phase 2 — Config Integration & UX (P1)

Bridge the gap between statically-configured auth providers and the dynamic gateway config system. Add dashboard pickers for config references.

---

#### P-04: OAuth2 Auth Provider Dynamic Config Bridge

**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `OAuth2TokenIntrospectGatewayFilterFactory.java`, `GatewayConfigRefResolver.java`, `Oauth2BearerTokenVerifier.java`

**Problem:** The `AUTH_OAUTH2` filter reads `Config.providerName` and looks it up in static `AuthProperties.oauth2Verification` YAML. The `GatewayConfigRefResolver.resolveAuthProvider()` correctly resolves `introspectUri`, `clientId`, `clientSecret` from the gateway config's auth providers section, but the filter doesn't read these fields — it only uses `providerName`.

**Changes:**
- **Backend:** Modify `OAuth2TokenIntrospectGatewayFilterFactory.Config` to accept `introspectUri`, `clientId`, `clientSecret` directly (in addition to `providerName`).
- **Backend:** In `apply()`, check if direct config fields are present (from `gatewayConfigRef` resolution). If so, use them directly for token introspection without going through `AuthProperties`. Fall back to `providerName` → `AuthProperties` lookup for backward compatibility.
- **Backend:** Update `GatewayConfigRefResolver.resolveAuthProvider()` to also map the provider `name` to `providerName` (not just `_providerName`) so existing filters using `providerName` config work.
- **Frontend:** Update `AUTH_OAUTH2` filter form to show a gateway config ref picker (Auth Provider dropdown) alongside the existing `providerName` text field.

---

#### P-05: mTLS/ClientID Migration to Dynamic Gateway Config

**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `MtlsAuthGatewayFilterFactory.java`, `ClientIdAuthGatewayFilterFactory.java`, `GatewayConfigRefResolver.java`

**Problem:** Both filters use legacy static config classes (`CertificateValuesConfig`, `NameValuesConfig` / `ClientProperties`) loaded from `application.yml`. There's no `GatewayConfigRefResolver` mapping for `AUTH_MTLS` or `AUTH_CLIENT_ID`, so these filters can't benefit from the dynamic gateway config system.

**Changes:**
- **Backend:** Add a new `GatewayConfigRefResolver` ref type: `MTLS_CLIENT_MAPPING` that resolves a list of client-ID-to-certificate mappings from the gateway config's auth providers section (type `MTLS`).
- **Backend:** Refactor `MtlsAuthGatewayFilterFactory` to accept a plain `Config` class with a `values` list, populated either from `gatewayConfigRef` resolution or from direct config. Keep backward compatibility with `CertificateValuesConfig` YAML.
- **Backend:** Similarly refactor `ClientIdAuthGatewayFilterFactory` to accept client mappings from either `gatewayConfigRef` or direct config. Add `CLIENT_ID_MAPPING` ref type.
- **Frontend:** Add config ref support to `AUTH_MTLS` and `AUTH_CLIENT_ID` filter forms.

---

#### P-06: Cert Vault Picker for Filter Config Forms

**Affected services:** `routify-admin-api`, `routify-dashboard`  
**Complexity:** S  
**Files:** `FilterConfigFields.tsx`, `FilterDefinitionForm.tsx`, `certVaultApi.ts`

**Problem:** Cert-related filters (`AUTH_CERT_VAULT`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`) require a `logicalId` that operators must type manually. The cert vault already exposes `QUEUE_CERTS_ACTIVE_LIST` which returns active certificates with their logical IDs.

**Changes:**
- **Backend (admin-api):** Expose a lightweight `GET /api/v1/admin/certs/logical-ids` endpoint that returns `[{logicalId, alias, status}]` from the cert vault via existing `CertVaultMessagingClient`.
- **Frontend:** Create a `CertLogicalIdPicker` component (dropdown with search) that fetches logical IDs from the new endpoint. Use it in `FilterConfigFields.tsx` for `AUTH_CERT_VAULT`, `CERT_ROTATION`, and `CERT_VAULT_EXPIRY_CHECK` filter types, replacing the free-text `logicalId` input.

---

#### P-07: Auth Provider Picker for Filter Forms

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `FilterConfigFields.tsx`, `gatewayApi.ts`

**Problem:** `AUTH_OAUTH2` and `DOWNSTREAM_BEARER_CC` filter forms have free-text `providerName` / `oauth2ProviderName` inputs. Operators don't know which auth providers are configured in the gateway config's Auth Providers tab.

**Changes:**
- **Frontend:** Create an `AuthProviderPicker` component that fetches the current gateway config's `authProviders` list (via existing `GET /api/v1/admin/gateway?section=authProviders`) and renders a dropdown. Filter by provider type (`OAUTH2_*` for `AUTH_OAUTH2`, all types for `AUTH_BASIC`).
- **Frontend:** Replace the free-text `providerName` input in `AUTH_OAUTH2` and `oauth2ProviderName` in `DOWNSTREAM_BEARER_CC` with the picker.

---

#### P-08: Downstream Credential Picker for Filter Forms

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `FilterConfigFields.tsx`, `gatewayApi.ts`

**Problem:** `DOWNSTREAM_BASIC_AUTH` filter form has free-text `username`/`password` inputs but could instead reference a downstream credential entry from the gateway config.

**Changes:**
- **Frontend:** Create a `DownstreamCredentialPicker` that fetches the gateway config's `downstreamCredentials` list and renders a dropdown. When selected, it auto-populates the `gatewayConfigRef` with `refType: 'DOWNSTREAM_CREDENTIAL'` and the credential's `refId`.
- **Frontend:** Add the picker to `DOWNSTREAM_BASIC_AUTH` and `DOWNSTREAM_BEARER_CC` filter forms as an alternative to manual credential entry.

---

#### P-09: Rate Limit Policy Picker for Filter Forms

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `FilterConfigFields.tsx`, `gatewayApi.ts`

**Problem:** `RATE_LIMIT_FIXED_WINDOW` and `RATE_LIMIT_SLIDING_WINDOW` filter forms require manual entry of `maxRequests`, `windowMs`, `keyResolver`. The gateway config's Rate Limiting tab stores reusable policies (`rateLimitPolicies`) that could be referenced via `gatewayConfigRef`.

**Changes:**
- **Frontend:** Create a `RateLimitPolicyPicker` that fetches `rateLimitPolicies` from the gateway config and renders a dropdown. When selected, set `gatewayConfigRef.refType = 'RATE_LIMIT_POLICY'` and `refId` to the policy ID. Show the policy's values as read-only fields with an "override" toggle.
- **Frontend:** Add the picker to both rate limit filter forms alongside the manual entry fields.

---

### Phase 3 — Completeness & Polish (P2)

Missing features and UX improvements that round out the platform.

---

#### P-10: Unified Gateway Filter Error Response Builder

**Overlaps with:** [gf-02 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#2-unified-error-response-builder)  
**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** All 12 filter factories that produce error responses

**Problem:** Each filter that short-circuits writes its own RFC 9457 response using `String.formatted()`. Inconsistencies:
- Some use `application/problem+json`, others use `application/json`
- JSON escaping is ad-hoc (`replace("\"", "'")` in AI filter vs `replace("\"", "\\\"")` in SpEL)
- Some include `errorCode`, others don't
- Rate limit rejections lack `Retry-After` header

**Changes:**
- **Backend:** Create `GatewayProblemResponse` utility with a reactive builder API. Use Jackson `ObjectMapper` for safe JSON serialization. Standardize `Content-Type: application/problem+json` on all error responses. Add `Retry-After` header on all 429 responses.
- **Backend:** Migrate all 12 filter factories to use the shared builder (one at a time, non-breaking).
- **Frontend:** None.

---

#### P-11: Rate Limiter X-RateLimit-* Response Headers

**Overlaps with:** [gf-03 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#3-rate-limiter-x-ratelimit--response-headers)  
**Affected services:** `routify-api-gateway`  
**Complexity:** S  
**Files:** `FixedWindowRateLimitGatewayFilterFactory.java`, `SlidingWindowRateLimitGatewayFilterFactory.java`

**Problem:** Neither rate limiter emits standard `X-RateLimit-*` headers. API consumers can't proactively slow down before hitting limits.

**Changes:**
- **Backend:** On every response (allow and reject), inject `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- **Backend:** Modify Lua scripts to return `{count, ttl}` tuple.
- **Backend:** Add `includeHeaders` config flag (default `true`).
- **Backend:** Add `Retry-After` on 429 responses.
- **Frontend:** None.

---

#### P-12: Workspace Create & Plan Management Dashboard Flow

**Affected services:** `routify-dashboard`, `routify-admin-api`  
**Complexity:** M  
**Files:** `WorkspacesPage.tsx`, `tenantsApi.ts`, `AdminTenantsController.java`

**Problem:** `WorkspacesPage.tsx` shows usage analytics (UsageOverview, UsageTrendChart) but has no "Create Workspace" action. Plan changes are not exposed in the UI.

**Changes:**
- **Frontend:** Add "Create Workspace" button and modal with fields: name, slug, plan (FREE/STARTER/PRO/ENTERPRISE), contact email. Use `CommandEvent.CreateTenant` via existing `tenantsApi.ts`.
- **Frontend:** Add "Edit Plan" button on workspace cards with plan selector and confirmation dialog.
- **Backend:** Verify `AdminTenantsController` exposes create tenant endpoint (it does via Kafka command). No backend changes needed if the endpoint exists.

---

#### P-13: Settings Module Completion

**Affected services:** `routify-dashboard`  
**Complexity:** S  
**Files:** `SettingsPage.tsx`

**Problem:** The settings module is a single `SettingsPage.tsx` file. Content and completeness needs verification and expansion.

**Changes:**
- **Frontend:** Audit `SettingsPage.tsx` content. Add sections for: platform version info, current user profile, password change (link to existing auth flow), RBAC feature flag status (`routify.rbac.granular-enabled`), export/import shortcuts, and theme preferences.

---

#### P-14: Filter Config `gatewayConfigRef` Validation

**Affected services:** `routify-route-service`, `routify-admin-api`  
**Complexity:** S  
**Files:** Route-service filter command handler, `RouteFilterMessagingClient.java`

**Problem:** When creating/updating a filter with a `gatewayConfigRef`, the route-service persists the ref without validating that the referenced entry (e.g., auth provider with ID `refId`) actually exists in the gateway config. The error only surfaces at gateway route-build time when `GatewayConfigRefResolver` logs a warning and returns empty.

**Changes:**
- **Backend (route-service):** On `CreateFilter` / `UpdateFilter` command processing, if `gatewayConfigRef` is present, query the gateway config via `GatewayConfigService` and validate that:
  1. The `refType` is a known type (`AUTH_PROVIDER`, `RATE_LIMIT_POLICY`, `CIRCUIT_BREAKER_DEFAULTS`, `RESILIENCE_DEFAULTS`, `VAULT_CERT`, `DOWNSTREAM_CREDENTIAL`).
  2. The `refId` matches an existing entry in the corresponding config section.
- **Backend:** Throw `RoutifyException.Validation` with a descriptive message on mismatch.
- **Frontend:** None (error will surface as a 422 validation error in the filter form).

---

#### P-15: Jolt Transform Response-Phase Support

**Overlaps with:** [gf-05 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#5-jolt-transform-response-phase-support)  
**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `JoltTransformGatewayFilterFactory.java`

**Problem:** The `phase` config param accepts `RESPONSE` but the implementation only handles `REQUEST`. Response transformation silently falls through.

**Changes:**
- **Backend:** Implement `ServerHttpResponseDecorator` that intercepts `writeWith()`, joins the response body, applies the Jolt `Chainr`, and rewrites the body. Update `Content-Length`. Only transform `application/json` responses.
- **Backend:** Add `maxBodySize` config (default 1 MB) — skip transformation for oversized bodies.
- **Frontend:** None (filter form already has `phase` dropdown with `REQUEST`/`RESPONSE` options).

---

#### P-16: RequestLogger Performance Improvements

**Overlaps with:** [gf-06 (Gateway Filters Roadmap)](./GATEWAY-FILTERS-ROADMAP.md#6-requestlogger-performance--configurability)  
**Affected services:** `routify-api-gateway`  
**Complexity:** M  
**Files:** `RequestLoggerGatewayFilterFactory.java`

**Problem:** Body capture buffers entire body (OOM risk); no sampling; hardcoded header redaction list; flat log format.

**Changes:**
- **Backend:** Add `maxBodyCaptureBytes` (default 4096, hard limit 64 KB), `samplingRate` (default 1.0), `headerAllowlist`/`headerDenylist`, `skipPaths` (regex patterns).
- **Frontend:** Update `REQUEST_LOGGER` filter config form to expose new fields.

---

### Phase 4 — Quality & Resilience (P3)

Testing, cleanup, and operational improvements.

---

#### P-17: Webhook Delivery Cleanup Scheduler

**Affected services:** `routify-identity-service`  
**Complexity:** S  
**Files:** Identity-service webhook package

**Problem:** Webhook delivery log retention is configurable via `routify.webhooks.delivery-retention-days` (default 7) but the cleanup may rely on manual DB maintenance rather than an active scheduled task.

**Changes:**
- **Backend:** Verify or add a `@Scheduled` task that runs daily and deletes webhook delivery records older than the configured retention period. Use `DELETE FROM webhook_deliveries WHERE created_at < NOW() - interval '? days'` with batch size limit.

---

#### P-18: Gateway Filter Unit Test Expansion

**Affected services:** `routify-api-gateway`  
**Complexity:** L  
**Files:** `src/test/java/io/routify/gateway/filter/`

**Problem:** Only 6 gateway filter unit tests exist (JwtAuth, FixedWindow, SlidingWindow, RequestTimeout, HeaderFilters, RateLimitKeyResolver). 23 filter factories have zero test coverage.

**Changes:**
- **Backend:** Add unit tests for all remaining filter factories. Priority order:
  1. Auth filters: `BasicAuth`, `ApiKeyAuth`, `CertVaultAuth`, `OAuth2TokenIntrospect` (security-critical)
  2. Modification filters: `RequestHeaderModify`, `ResponseHeaderModify`
  3. Transformation: `JoltTransform`, `JsonSchemaValidate`
  4. Custom: `SpelCustom` (especially post-sandboxing)
  5. AI: `AiFilter`, `AiModifier` (mock RabbitMQ)
  6. Remaining: `Timeout`, `ConditionalRoute`, `ApiVersioning`, etc.

---

#### P-19: Backend Service Integration Test Expansion

**Affected services:** All services with zero tests (audit-service, cert-vault, ai-service, gitops-agent)  
**Complexity:** L  
**Files:** New test classes per service

**Problem:** 4 of 8 services have zero tests. Existing ITs are disabled by default due to Docker Engine 29.x / Testcontainers incompatibility.

**Changes:**
- **Backend:** Verify the Docker Engine 29.x / Testcontainers incompatibility is resolved with `docker-java` 3.7.1 override.
- **Backend:** Add integration test base classes for:
  - `AuditServiceIntegrationBase` — Postgres + Kafka + RabbitMQ
  - `CertVaultIntegrationBase` — Postgres + Kafka + RabbitMQ
- **Backend:** Write ITs for critical paths:
  - audit-service: event persistence, DLQ consumption, alert rule evaluation
  - cert-vault: certificate upload/revoke lifecycle, ACME operations
  - identity-service: API key lifecycle (create → Redis projection → revoke)

---

#### P-20: Frontend Unit Test Expansion

**Affected services:** `routify-dashboard`  
**Complexity:** L  
**Files:** `src/__tests__/` and per-module test files

**Problem:** Only 4 unit tests exist. 16 modules, 17 API clients, and 4 hooks have no test coverage.

**Changes:**
- **Frontend:** Add Vitest tests for:
  1. All API clients (`src/api/*.ts`) — mock `apiClient`, verify request construction and response parsing
  2. Custom hooks (`useWebSocket`, `useRealtimeQuery`, `useBootstrapAuth`)
  3. Store logic (`authStore`, `wsStore`)
  4. Key components per module (at least the main page + form for each module)
- **Frontend:** Target: ≥1 test file per API client, ≥1 test per hook, ≥1 component test per module.

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

#### P-22: `routify-common` Shared Utilities Test Expansion

**Affected services:** `routify-common`  
**Complexity:** M  
**Files:** `src/test/java/io/routify/common/`

**Problem:** 5 tests exist (CommandEvent serialization, QueryMessage serialization, GlobalExceptionHandler, KafkaDlqErrorHandler, SecurityContext). Missing tests for `PageResponse`, `Sensitive`/`SensitiveField`, `RoutifyHeaders.resolveActor()`, `RedisKeys` constants, domain enum validation.

**Changes:**
- **Backend:** Add unit tests for:
  - `PageResponse.of()` / `PageResponse.from()` factory methods
  - `Sensitive.maskFields()` with nested objects and collections
  - `Sensitive.isMasked()` edge cases
  - `RoutifyHeaders.resolveActor()` resolution order
  - `TenantPlan` quota values
  - `FilterType` ↔ `RouteDefinitionBuilder` switch exhaustiveness

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
  P-01 JWT Hardening ─────────────────────────────── standalone
  P-02 SpEL Sandboxing ──────────────────────────── standalone
  P-03 BasicAuth Password Hashing ────────────────── standalone

Phase 2 (P1 — Config Integration)
  P-04 OAuth2 Dynamic Config Bridge ───────────────── requires P-07 (Auth Provider Picker)
  P-05 mTLS/ClientID Dynamic Config ──────────────── standalone
  P-06 Cert Vault Picker ─────────────────────────── standalone
  P-07 Auth Provider Picker ──────────────────────── standalone (enables P-04)
  P-08 Downstream Credential Picker ──────────────── standalone
  P-09 Rate Limit Policy Picker ──────────────────── standalone

Phase 3 (P2 — Completeness)
  P-10 Unified Error Response Builder ─────────────── standalone (enables gf-03, gf-09, gf-11)
  P-11 Rate Limiter Headers ──────────────────────── depends on P-10
  P-12 Workspace Create & Plan Management ─────────── standalone
  P-13 Settings Module Completion ─────────────────── standalone
  P-14 Filter gatewayConfigRef Validation ─────────── standalone
  P-15 Jolt Response-Phase ────────────────────────── standalone
  P-16 RequestLogger Improvements ─────────────────── standalone

Phase 4 (P3 — Quality)
  P-17 Webhook Delivery Cleanup ───────────────────── standalone
  P-18 Gateway Filter Test Expansion ──────────────── depends on P-01, P-02 (test post-fix)
  P-19 Backend Service IT Expansion ───────────────── standalone
  P-20 Frontend Unit Test Expansion ───────────────── standalone
  P-21 Frontend E2E Test Expansion ────────────────── depends on P-20
  P-22 routify-common Test Expansion ──────────────── standalone
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
| **P-01 JWT hardening breaks dev workflows** | Medium | Medium | Add `routify.jwt.allow-unsigned: true` explicit opt-in for dev profile only. Fail-closed by default. Document migration in release notes. |
| **P-02 SpEL sandboxing breaks existing expressions** | Medium | Medium | Log warnings for expressions that reference `#request` for 1 release before removal. Provide `#clientIp` and `#contentType` as replacements. |
| **P-03 BCrypt hashing breaks existing BasicAuth configs** | Medium | Low | On first gateway reload after upgrade, detect unhashed passwords (no `$2a$` prefix) and reject with a clear migration error. Provide a migration script. |
| **P-04 OAuth2 config migration is a breaking change** | Low | Medium | Purely additive — existing `providerName` → YAML path continues to work. New direct-config path is opt-in via `gatewayConfigRef`. |
| **P-05 mTLS/ClientID refactoring breaks existing deployments** | Medium | High | Keep backward compatibility with `CertificateValuesConfig` YAML. New dynamic config is an additional path, not a replacement. |
| **P-14 gatewayConfigRef validation blocks valid saves** | Low | Medium | Only validate `refType` is known and `refId` format is valid. Log a warning (don't block) if the referenced entry isn't found — it may be created later. |
| **P-19 IT expansion delayed by Docker/TC incompatibility** | Medium | Low | Verify `docker-java` 3.7.1 resolves the issue. If not, target unit tests only. |
| **Test expansion initiatives (P-18 through P-23) are large** | High | Low | Break each into sub-tasks (one test class per PR). Prioritize security-critical paths first. |

