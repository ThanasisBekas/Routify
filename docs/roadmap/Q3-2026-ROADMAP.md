# Routify — Q3 2026 Quarterly Roadmap

> **Period:** April 7 – June 30, 2026  
> **Platform version baseline:** 2.0.0-SNAPSHOT  
> **Goal:** Harden operational reliability, expand self-service capabilities, and deepen observability — all delivered incrementally behind feature flags where possible.

---

## Roadmap Summary

| # | Initiative | Theme | Target Sprint | Impact |
|---|-----------|-------|---------------|--------|
| 1 | ✅ [API Key Lifecycle Management](#1-api-key-lifecycle-management) | Security | Weeks 1–3 | Enables programmatic API access without JWT; unblocks CI/CD and M2M integrations |
| 2 | ✅ [Route Promotion Environments](#2-route-promotion-environments) | Reliability | Weeks 2–5 | Staging → Production promotion flow prevents misconfigured routes hitting live traffic |
| 3 | ✅ [Webhook Notification System](#3-webhook-notification-system) | Integration | Weeks 3–5 | External systems react to route, cert, and audit events in real time |
| 4 | [Granular RBAC & Permissions](#4-granular-rbac--permissions) | Security | Weeks 4–7 | Fine-grained permissions per resource type replace the current 4-role model |
| 5 | [Gateway Health Dashboard v2](#5-gateway-health-dashboard-v2) | Observability | Weeks 5–8 | Live latency heatmaps, per-route error budgets, and SLO tracking in the dashboard |
| 6 | [Automated Certificate Lifecycle](#6-automated-certificate-lifecycle) | Operations | Weeks 7–9 | ACME/Let's Encrypt auto-renewal eliminates manual cert rotation |
| 7 | [Route Import / Export & GitOps](#7-route-import--export--gitops) | Developer Experience | Weeks 8–11 | Declarative YAML route definitions enable version-controlled gateway configuration |
| 8 | [Integration Test Coverage Expansion](#8-integration-test-coverage-expansion) | Quality | Ongoing | Systematic IT coverage across all admin-api endpoints and gateway filter factories |

Each initiative has a dedicated design document in [`docs/roadmap/initiatives/`](./initiatives/) with full implementation steps.

---

## 1. API Key Lifecycle Management

**Design doc:** [`initiatives/01-api-key-lifecycle.md`](./initiatives/01-api-key-lifecycle.md)

### Description
The platform currently supports API key *authentication* at the gateway (`AUTH_API_KEY` filter, `RedisKeys.APIKEY_PREFIX`) but API keys are seeded manually via `redis-cli`. This initiative adds full CRUD lifecycle management — create, list, revoke, rotate — through the dashboard and admin-api, with keys stored durably in `routify-identity-service` and projected to Redis for gateway reads.

### Expected Impact
- **Unblocks machine-to-machine (M2M) integrations** — CI pipelines, partner APIs, and IoT devices can authenticate without JWT token exchange.
- **Eliminates manual Redis operations** — operators manage keys through the dashboard like any other resource.
- **Improves security posture** — keys have configurable expiry, scoped roles, automatic rotation reminders, and full audit trail.

### High-Level Implementation Plan
1. **Identity-service domain model** — `ApiKey` entity with `hashedKey`, `tenantId`, `userId`, `role`, `email`, `expiresAt`, `status`, `lastUsedAt`. Flyway migration `V__api_keys.sql`.
2. **Identity-service command handler** — consume `CommandEvent.CreateApiKey / RevokeApiKey / RotateApiKey` from new Kafka topic `KafkaTopics.APIKEY_COMMANDS`.
3. **Redis projection** — on key create/rotate, write to `RedisKeys.APIKEY_PREFIX + rawKey` as a Redis Hash. On revoke, delete the key. Existing `ApiKeyAuthGatewayFilterFactory` reads remain unchanged.
4. **RabbitMQ query handlers** — `QUEUE_APIKEYS_QUERY`, `QUEUE_APIKEYS_GET` on `EXCHANGE_IDENTITY_SERVICE` for paginated list and detail views.
5. **Admin-api controller + messaging client** — `AdminApiKeysController` at `/api/v1/admin/api-keys`. Kafka commands for writes, RabbitMQ for reads.
6. **Dashboard module** — `src/modules/api-keys/` with list page, create dialog (shows raw key once), revoke confirmation, rotate flow.
7. **Audit integration** — `AUDIT_EVENTS` entries for every key lifecycle action.
8. **API module** — `src/api/apiKeysApi.ts` with typed request/response DTOs in `src/types/index.ts`.

---

## 2. Route Promotion Environments

**Design doc:** [`initiatives/02-route-promotion.md`](./initiatives/02-route-promotion.md)

### Description
Add a lightweight *environment* concept (`STAGING`, `PRODUCTION`) to routes so operators can test configuration changes in a non-production context before promoting them to live traffic. The gateway applies environment-tagged routes only when the incoming request matches the environment selector (header or path prefix).

### Expected Impact
- **Reduces production incidents** caused by misconfigured routes — every change is validated in staging first.
- **Enables blue/green route deployments** without duplicate route definitions.
- **Provides auditability** — promotion events are tracked with before/after snapshots.

### High-Level Implementation Plan
1. **Domain model extension** — add `environment` column (`STAGING` | `PRODUCTION`, default `PRODUCTION`) to `route` table in `routify` schema. Flyway migration.
2. **Route-service changes** — `CommandEvent.PromoteRoute` handler copies staging route config → production version, increments version, publishes `ROUTE_EVENTS`.
3. **Gateway route matching** — `RouteDefinitionBuilder` attaches an environment predicate. Staging routes match only when `X-Route-Environment: STAGING` header is present (configurable).
4. **Admin-api endpoint** — `POST /api/v1/admin/routes/{id}/promote` dispatches `PromoteRoute` Kafka command.
5. **Dashboard UI** — environment badge on route cards, "Promote to Production" action button with diff preview modal, environment filter toggle in route list.
6. **Audit** — promotion generates a dedicated audit event with snapshot diff payload.

---

## 3. Webhook Notification System

**Design doc:** [`initiatives/03-webhook-notifications.md`](./initiatives/03-webhook-notifications.md)

### Description
Allow tenants to register webhook endpoints that receive HTTP POST callbacks when specific platform events occur (route activated, cert expiring, AI filter blocked, DLQ overflow). This replaces the need for consumers to poll the SSE/WebSocket event stream.

### Expected Impact
- **Enables integration with external incident tools** (PagerDuty, Slack, OpsGenie) without custom adapter code.
- **Supports multi-cloud deployments** where WebSocket connectivity is restricted.
- **Builds toward a public event API** for partner integrations.

### High-Level Implementation Plan
1. **Domain model** — `WebhookSubscription` entity in `routify_identity` schema: `id`, `tenantId`, `url`, `secret` (HMAC), `eventTypes[]`, `status`, `failureCount`, `lastDeliveredAt`. Flyway migration.
2. **Kafka consumer** — new `WebhookDispatchConsumer` in identity-service subscribes to `ROUTE_EVENTS`, `FILTER_EVENTS`, `CERT_EVENTS`, `AUDIT_EVENTS`, `AI_FILTER_DECISIONS`. Matches events against registered subscriptions.
3. **HTTP delivery** — POST with JSON payload + `X-Routify-Signature` (HMAC-SHA256 of body using subscription secret). Retry with exponential backoff (3 attempts). Circuit-breaker per subscription URL.
4. **Admin-api endpoints** — CRUD at `/api/v1/admin/webhooks`. Kafka commands for writes, RabbitMQ queries for reads.
5. **Dashboard module** — `src/modules/webhooks/` with subscription list, create/edit form (event type checkboxes), delivery log, test ping button.
6. **Delivery log** — webhook delivery attempts persisted in `routify_identity` for debugging (retained 7 days).

---

## 4. Granular RBAC & Permissions

**Design doc:** [`initiatives/04-granular-rbac.md`](./initiatives/04-granular-rbac.md)

### Description
Extend the current 4-role model (`SUPER_ADMIN`, `TENANT_ADMIN`, `OPERATOR`, `VIEWER`) with fine-grained permission sets. Each role maps to a set of permission strings (e.g., `routes:write`, `filters:read`, `certs:admin`). Custom roles can be created per tenant.

### Expected Impact
- **Least-privilege access** — operators get exactly the permissions they need without over-provisioning.
- **Compliance readiness** — auditors can verify who has access to what, down to the resource type.
- **Tenant autonomy** — tenant admins create custom roles without super-admin intervention.

### High-Level Implementation Plan
1. **Permission catalog** — `Permission` enum in `routify-common/domain` with values like `ROUTES_READ`, `ROUTES_WRITE`, `ROUTES_ACTIVATE`, `FILTERS_READ`, `FILTERS_WRITE`, `CERTS_READ`, `CERTS_ADMIN`, `USERS_READ`, `USERS_WRITE`, `AUDIT_READ`, `AUDIT_REPLAY`, `GATEWAY_CONFIG`, `API_KEYS_ADMIN`, `WEBHOOKS_ADMIN`, `AI_POLICY_WRITE`.
2. **Role-permission mapping table** — `role_permission` in `routify_identity` schema. Seed built-in roles with default permission sets. Flyway migration.
3. **JWT claims enrichment** — add `permissions` claim (string array) to JWT. Update `SecurityContext` to expose `hasPermission(String)`.
4. **Service-side authorization** — replace `@PreAuthorize("hasRole(...)")` with `@PreAuthorize("hasAuthority(...)")` using Spring Security authority mapping from JWT permissions.
5. **Admin-api + dashboard** — role management page at `/api/v1/admin/roles`. Edit built-in roles' permission mappings. Create custom roles (TENANT_ADMIN+ only).
6. **Backward compatibility** — existing JWTs without `permissions` claim fall back to role-derived defaults. Feature flag `routify.rbac.granular-enabled: false` to gate rollout.

---

## 5. Gateway Health Dashboard v2

**Design doc:** [`initiatives/05-gateway-health-v2.md`](./initiatives/05-gateway-health-v2.md)

### Description
Redesign the gateway status page (`src/modules/gateway/`) with live latency heatmaps, per-route error budget tracking, SLO status indicators, and a unified circuit-breaker visualization. Data is sourced from the existing Prometheus/Micrometer metrics + live WebSocket stream.

### Expected Impact
- **Faster incident triage** — operators see which routes are degraded at a glance, with drill-down to individual request traces (Tempo link-out).
- **SLO-driven operations** — teams set availability/latency targets and the dashboard alerts when error budget is consumed.
- **Reduces dependency on Grafana** for day-to-day gateway monitoring.

### High-Level Implementation Plan
1. **Backend metrics aggregation** — new `DashboardStatsService` method `getRouteHealthSummary(tenantId)` that queries audit-service via RabbitMQ for per-route p50/p95/p99 latency, error rate, and request volume (last 1h/24h/7d).
2. **SLO configuration model** — `route_slo` table in `routify` schema: `routeId`, `availabilityTarget` (e.g., 99.9), `latencyP99TargetMs`, `evaluationWindowHours`. Managed via route-service.
3. **Error budget calculation** — admin-api computes remaining error budget: `budget = 1 - (actual_error_rate / (1 - target/100))`. Exposed at `GET /api/v1/admin/routes/{id}/slo-status`.
4. **Dashboard redesign** — Recharts latency heatmap (time × routes, color = p99), sparkline per route, circuit-breaker state cards (from `wsStore` live data), SLO health bar.
5. **Real-time updates** — extend `/topic/metrics` WebSocket payload with per-route latency buckets. Dashboard subscribes and updates heatmap without polling.
6. **Tempo trace link-out** — each request log row includes a "View Trace" link constructed from `correlationId` → Tempo query URL.

---

## 6. Automated Certificate Lifecycle

**Design doc:** [`initiatives/06-auto-cert-lifecycle.md`](./initiatives/06-auto-cert-lifecycle.md)

### Description
Integrate ACME protocol support (Let's Encrypt / ZeroSSL) into `routify-cert-vault` so certificates can be automatically issued, renewed, and rotated without operator intervention. Builds on the existing `CertOutboxPoller` → Kafka → gateway hot-reload pipeline.

### Expected Impact
- **Eliminates manual cert rotation** — the #1 operational burden reported for TLS-heavy deployments.
- **Prevents outages from expired certificates** — auto-renewal triggers 30 days before expiry.
- **Maintains the existing security model** — private keys remain AES-encrypted in cert-vault; only decrypted PEM is fetched by the gateway via `QUEUE_CERTS_FETCH_MATERIAL`.

### High-Level Implementation Plan
1. **ACME client library** — add `org.shredzone:acme4j` dependency to `routify-cert-vault/pom.xml`.
2. **Domain model** — `AcmeAccount` entity (account URL, key pair), `AcmeOrder` entity (domain, status, challenge type, last renewed). Flyway migrations.
3. **ACME challenge solver** — HTTP-01 challenge: cert-vault exposes `/.well-known/acme-challenge/{token}` endpoint. DNS-01: webhook to tenant's DNS provider (future).
4. **Renewal scheduler** — `AcmeRenewalScheduler` runs daily, queries certs with `notAfter` within 30 days, initiates renewal. On success: stores new cert via existing `CertOutboxPoller` → Kafka → gateway pipeline.
5. **Admin-api endpoints** — `POST /api/v1/admin/certs/acme/register` (account setup), `POST /api/v1/admin/certs/acme/issue` (manual trigger), `GET /api/v1/admin/certs/acme/status`.
6. **Dashboard** — ACME tab in certificates module: domain input, auto-renew toggle, renewal history timeline, challenge status indicator.
7. **Monitoring** — new `routify.cert.acme.renewals` counter and `routify.cert.acme.failures` counter in `RoutifyMetrics`.

---

## 7. Route Import / Export & GitOps

**Design doc:** [`initiatives/07-route-gitops.md`](./initiatives/07-route-gitops.md)

### Description
Enable declarative route management via YAML/JSON export and import. Operators can export their entire route+filter configuration, version-control it in Git, and re-import it into another environment (or the same one for disaster recovery). This is the foundation for a future GitOps reconciliation loop.

### Expected Impact
- **Disaster recovery** — full gateway configuration can be restored from a YAML file in seconds.
- **Environment parity** — dev/staging/production environments stay in sync via checked-in config.
- **Enables infrastructure-as-code workflows** — CI pipelines apply route changes via API.

### High-Level Implementation Plan
1. **Export format** — YAML schema: `routify/v1` header, list of route definitions with embedded filter configs, gateway config section. Schema documented in `docs/schema/route-export-v1.yaml`.
2. **Export endpoint** — `GET /api/v1/admin/routes/export?format=yaml` on admin-api. Queries route-service via RabbitMQ for full snapshot, serializes to YAML using SnakeYAML.
3. **Import endpoint** — `POST /api/v1/admin/routes/import` accepts YAML body. Validates schema, generates `CommandEvent.CreateRoute` / `CommandEvent.CreateFilter` commands for each entry. Idempotent: uses route `name` as natural key, skips duplicates or updates existing.
4. **Diff preview** — `POST /api/v1/admin/routes/import/preview` returns a list of changes that *would* be applied (created, updated, unchanged, deleted) without executing them.
5. **Dashboard module** — export button (downloads `.yaml`), import dialog with file drop zone and diff table, apply/cancel actions.
6. **CLI companion** — document `curl`-based import/export for CI pipeline usage in `docs/cli-examples.md`.
7. **SnakeYAML dependency** — add to `routify-admin-api/pom.xml` (already transitively available via Spring Boot, but declare explicitly for clarity).

---

## 8. Integration Test Coverage Expansion

**Design doc:** [`initiatives/08-test-expansion.md`](./initiatives/08-test-expansion.md)

### Description
Systematically expand `*IT.java` integration test coverage in `routify-admin-api` from the current 2 test classes (`AdminAuthEndpointIT`, `AdminRoutesEndpointIT`) to cover all 12 controller endpoints and critical cross-service flows. Unblock by resolving the Docker Engine 29.x / Testcontainers incompatibility.

### Expected Impact
- **Catches regressions before release** — especially in the Kafka command → RabbitMQ reply contract between admin-api and downstream services.
- **Enables confident refactoring** — the test suite becomes the safety net for all Q3 initiatives.
- **CI/CD readiness** — ITs run in the CI pipeline on every merge to `develop`.

### High-Level Implementation Plan
1. **Unblock Testcontainers** — verify `docker-java` 3.7.1 override resolves Docker Engine 29.x issue. If not, pin Testcontainers to 1.22.x. Flip `<skipITs>false</skipITs>` in CI profile.
2. **Test classes to add** (one per controller, extending `AdminApiIntegrationBase`):
   - `AdminFiltersEndpointIT` — filter CRUD + attach/detach from route
   - `AdminUsersEndpointIT` — user CRUD + password change
   - `AdminTenantsEndpointIT` — tenant create, suspend, reactivate, list-active
   - `AdminCertificatesEndpointIT` — cert upload, revoke, gateway-snapshot
   - `AdminCertGroupsEndpointIT` — group CRUD + member management
   - `AdminAuditEndpointIT` — event query, request query, stats
   - `AdminReplayEndpointIT` — single replay, bulk replay, stats
   - `AdminAiFilterEndpointIT` — test-policy dry run, stats query
   - `AdminAiModifierEndpointIT` — test-modification dry run
   - `AdminGatewayConfigEndpointIT` — config get/save per section
3. **Gateway filter factory tests** — per-filter factory unit tests in `routify-api-gateway` using `MockServerWebExchange` for the top-10 most used filters.
4. **CI pipeline integration** — add `mvn verify -DskipITs=false` step to GitHub Actions workflow on `develop` and `release/*` branches.

---

## Dependency & Sequencing Map

```
Week 1   Week 2   Week 3   Week 4   Week 5   Week 6   Week 7   Week 8   Week 9   Week 10  Week 11  Week 12
──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ────────
█████████████████████████                                                                           1. API Key Lifecycle
         ████████████████████████████████████                                                        2. Route Promotion
                  ████████████████████████████                                                       3. Webhooks
                           ████████████████████████████████████████                                  4. Granular RBAC
                                    █████████████████████████████████████████                        5. Health Dashboard v2
                                                              █████████████████████████              6. Auto Cert Lifecycle
                                                                       █████████████████████████████ 7. Route Import/Export
████████████████████████████████████████████████████████████████████████████████████████████████████ 8. Test Coverage (ongoing)
```

### Key Dependencies
- **Initiative 4 (RBAC)** depends on **Initiative 1 (API Keys)** for permission scoping of API key operations.
- **Initiative 6 (Auto Certs)** depends on **Initiative 5 (Health Dashboard)** for cert expiry visualization.
- **Initiative 7 (GitOps)** depends on **Initiative 2 (Environments)** to support per-environment export.
- **Initiative 8 (Tests)** is a continuous effort that validates all other initiatives.

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| ACME challenge solver requires gateway endpoint exposure | Medium | High | Implement HTTP-01 as a dedicated cert-vault endpoint behind `/well-known/`; document firewall rules |
| Granular RBAC JWT size increase (permissions array) | Low | Medium | Use permission bitfield encoding if claim exceeds 1 KB; or fetch permissions from Redis on first request |
| Route promotion adds complexity to gateway route matching | Medium | Medium | Environment predicate is opt-in; default is `PRODUCTION` so existing routes are unaffected |
| Docker Engine 29.x incompatibility persists with TC 1.21.4 | Low | High | Fallback: pin to TC 1.22.x or use Docker Desktop 4.x for CI runners |

---

## Success Metrics

| Metric | Baseline (Q2) | Target (Q3 End) |
|--------|---------------|-----------------|
| Admin-api IT coverage (controller endpoints) | 2/12 (17%) | 12/12 (100%) |
| Manual cert rotation operations per month | ~15 | < 2 (ACME auto-renewal) |
| Mean time to detect misconfigured route | Hours (production reports) | < 5 min (staging promotion) |
| API key provisioning time | Manual (minutes, Redis CLI) | < 30s (dashboard self-service) |
| Dashboard gateway health page load time | N/A (basic status) | < 2s with live heatmap |

