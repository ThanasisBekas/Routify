# Routify — Q4 2026 Quarterly Roadmap

> **Period:** July 6 – September 25, 2026  
> **Platform version baseline:** 2.1.0-SNAPSHOT (post-Q3)  
> **Prerequisite:** Q3 roadmap initiatives delivered — API Key Lifecycle, Route Promotion, Webhooks, Granular RBAC, Gateway Health v2, ACME Certs, Import/Export, expanded IT coverage.  
> **Goal:** Scale the platform for multi-cluster production deployments, extend AI capabilities, enforce tenant quotas, and close the GitOps loop — transforming Routify from a single-instance gateway into a production-grade platform service.

---

## Roadmap Summary

| # | Initiative | Theme | Target Sprint | Impact |
|---|-----------|-------|---------------|--------|
| 1 | [GitOps Reconciliation Agent](#1-gitops-reconciliation-agent) | Developer Experience | Weeks 1–4 | Closes the GitOps loop: Git merges auto-apply gateway config changes |
| 2 | [Tenant Quota Enforcement & Usage Analytics](#2-tenant-quota-enforcement--usage-analytics) | Multi-tenancy | Weeks 1–4 | `TenantPlan` quotas enforced at gateway and service layers with live usage dashboards |
| 3 | [Multi-Gateway Cluster Awareness](#3-multi-gateway-cluster-awareness) | Scalability | Weeks 3–6 | Multiple gateway instances share consistent route state with leader-aware config sync |
| 4 | [AI Policy Playground & Prompt Versioning](#4-ai-policy-playground--prompt-versioning) | AI / Intelligence | Weeks 4–7 | Visual prompt editor with version history, A/B testing, and accuracy scoring |
| 5 | [GraphQL Analytics API](#5-graphql-analytics-api) | Integration | Weeks 5–8 | Flexible, composable analytics queries replace the fixed-schema REST stats endpoints |
| 6 | [Canary Routing & Traffic Splitting](#6-canary-routing--traffic-splitting) | Reliability | Weeks 6–9 | Weighted traffic splitting between route versions for progressive delivery |
| 7 | [Platform Alerting Engine](#7-platform-alerting-engine) | Operations | Weeks 8–11 | Threshold-based alerting on SLOs, error rates, DLQ depth, and cert expiry — delivered via webhooks |
| 8 | [Helm Chart & Kubernetes-Native Deployment](#8-helm-chart--kubernetes-native-deployment) | Infrastructure | Weeks 9–12 | Production-ready Helm chart with HPA, PDB, ConfigMaps, Secrets, and init containers |

Each initiative has a dedicated design document in [`docs/roadmap/initiatives/`](./initiatives/).

---

## 1. GitOps Reconciliation Agent

**Design doc:** [`initiatives/09-gitops-agent.md`](./initiatives/09-gitops-agent.md)

### Description
Build on the Q3 Import/Export foundation (Initiative 07) by adding an autonomous reconciliation agent. The agent watches a Git repository for changes to `routify-export.yaml`, validates them via the import preview endpoint, applies them via the import endpoint, and reports results via webhooks (Initiative 03). This completes the declarative infrastructure-as-code story.

### Expected Impact
- **Fully declarative gateway management** — merge a PR to change a route, and the gateway reconfigures itself within seconds.
- **Audit-grade change tracking** — every Git commit becomes an auditable change record with author, timestamp, and diff.
- **Eliminates config drift** — the Git repository is the single source of truth; manual dashboard changes are overwritten on the next reconciliation cycle.

### High-Level Implementation Plan
1. **New module `routify-gitops-agent`** — standalone Spring Boot service (port 8087) that polls a Git repository on a configurable interval (default: 60s) or reacts to a GitHub/GitLab webhook push event.
2. **Git integration** — JGit library for cloning/pulling; supports HTTPS + SSH auth; configurable branch, path-to-yaml, and repository URL via `routify.gitops.*` properties.
3. **Reconciliation loop** — on each cycle: pull latest → compute SHA-256 of target YAML → compare with last-applied hash → if changed, call admin-api `/api/v1/admin/routes/import/preview` → if valid and non-empty diff, call `/api/v1/admin/routes/import` → store applied hash + result in local state (Redis key or embedded H2).
4. **Webhook reporting** — on apply success/failure, fire webhook to configured URL (reuses Q3 webhook infrastructure). Also publishes a `DomainEvent.GitOpsReconciled` to `KafkaTopics.AUDIT_EVENTS`.
5. **Dashboard status page** — `src/modules/gitops/` showing last sync time, last applied commit, diff summary, and reconciliation history.
6. **API key authentication** — the agent authenticates to admin-api using an API key (from Q3 Initiative 01), not a user JWT.
7. **Drift detection mode** — optional mode that only *detects* drift without applying, publishing a `DRIFT_DETECTED` webhook event. Operators can then manually approve via the dashboard.

---

## 2. Tenant Quota Enforcement & Usage Analytics

**Design doc:** [`initiatives/10-tenant-quotas.md`](./initiatives/10-tenant-quotas.md)

### Description
The `TenantPlan` enum defines quotas (`maxRoutes`, `maxFilters`, `monthlyRequestQuota`) but they are not enforced at runtime. A `FREE` tenant can create unlimited routes via direct API calls. This initiative adds real-time quota enforcement at both the service layer (route/filter creation) and the gateway layer (monthly request counting), plus a tenant usage analytics dashboard.

### Expected Impact
- **Enables commercial multi-tenancy** — plans map to paid tiers with hard limits.
- **Prevents resource abuse** — free-tier tenants cannot overwhelm shared infrastructure.
- **Provides usage visibility** — tenant admins see consumption against their plan limits, enabling self-service plan upgrades.

### High-Level Implementation Plan
1. **Service-layer enforcement** — route-service checks `currentRouteCount < plan.maxRoutes()` before executing `CreateRoute` commands; filter-service checks `currentFilterCount < plan.maxFilters()`. Reject with `RoutifyException.QuotaExceeded` (HTTP 429).
2. **Gateway request counting** — Redis-backed monthly counter per tenant (`routify:quota:{tenantId}:{YYYY-MM}`) incremented by `TenantContextGatewayFilterFactory` on every request. When `count >= plan.monthlyRequestQuota()`, return HTTP 429 with `Retry-After` header.
3. **Usage tracking service** — audit-service aggregates daily snapshots of route count, filter count, and request volume per tenant. Stored in `routify_audit.tenant_usage_daily` table.
4. **Admin-api endpoints** — `GET /api/v1/admin/tenants/{id}/usage` returns current period usage vs limits. `GET /api/v1/admin/tenants/{id}/usage/history` returns daily usage trend (30d).
5. **Dashboard module** — `src/modules/workspaces/` enhanced with usage progress bars (routes used/limit, filters used/limit, requests used/limit), plan comparison table, and usage trend chart.
6. **Quota exceeded webhook** — when a tenant hits 80% or 100% of any quota, fire a `QUOTA_WARNING` / `QUOTA_EXCEEDED` webhook event.
7. **Plan upgrade flow** — dashboard UI for plan comparison and upgrade request (admin approval).

---

## 3. Multi-Gateway Cluster Awareness

**Design doc:** [`initiatives/11-multi-gateway.md`](./initiatives/11-multi-gateway.md)

### Description
Currently, multiple gateway instances consume the same Kafka topics and maintain independent in-memory route registries. However, there is no cluster-level coordination — no awareness of peer instances, no consistent config version, and no ability to query fleet-wide status. This initiative adds cluster awareness, consistent config versioning, and a fleet status view.

### Expected Impact
- **Confident horizontal scaling** — operators can scale gateway instances knowing all share the same config version.
- **Fleet-wide visibility** — dashboard shows all gateway instances with their config version, health, and last-reload timestamp.
- **Prevents split-brain routing** — config version pinning ensures no instance serves stale routes.

### High-Level Implementation Plan
1. **Gateway instance registry** — each gateway instance registers itself in Redis on startup (`routify:gateway:instances:{instanceId}` with TTL=30s, heartbeat every 10s). Fields: `instanceId`, `hostname`, `port`, `configVersion`, `routeCount`, `startedAt`, `lastReloadAt`.
2. **Config version tracking** — gateway maintains a monotonically increasing `configVersion` counter in Redis (`routify:gateway:config-version`), incremented on each route/filter event consumption. Instances compare their local version against the shared version on heartbeat.
3. **Fleet status RPC** — admin-api queries all registered gateway instances via RabbitMQ fanout (or iterates the Redis set and sends RPC to each). Response includes per-instance route count, config version, health, and active connection count.
4. **Admin-api endpoint** — `GET /api/v1/admin/gateway/fleet` returns fleet status.
5. **Dashboard fleet view** — `src/modules/gateway/tabs/FleetTab.tsx` showing instance cards with version badge, uptime, route count, and health indicator. Stale instances (configVersion < global) highlighted in amber.
6. **Config consistency alert** — if any instance's `configVersion` is behind the global version for >60s, publish a `GATEWAY_CONFIG_DRIFT` webhook event.

---

## 4. AI Policy Playground & Prompt Versioning

**Design doc:** [`initiatives/12-ai-playground.md`](./initiatives/12-ai-playground.md)

### Description
The current AI filter and modifier services use operator-supplied prompts stored as filter config strings. There is no way to version prompts, compare accuracy across versions, or interactively test prompts against sample traffic. This initiative adds a visual prompt editor with version history, A/B testing between prompt versions, and accuracy scoring based on historical decision audit data.

### Expected Impact
- **Faster prompt iteration** — operators test and refine policies in a sandbox before deploying to live traffic.
- **Measurable AI quality** — accuracy scores based on ground-truth feedback enable data-driven prompt engineering.
- **Safe rollouts** — A/B testing routes a percentage of traffic to a new prompt version, measuring ALLOW/BLOCK accuracy before full rollout.

### High-Level Implementation Plan
1. **Prompt versioning model** — `ai_prompt_version` table in `routify_audit` schema: `id`, `filterId`, `version`, `promptText`, `createdBy`, `createdAt`, `status` (DRAFT/ACTIVE/ARCHIVED). Each filter has one ACTIVE version at a time.
2. **Version CRUD** — new RabbitMQ queries and Kafka commands for prompt version management. Admin-api endpoints at `/api/v1/admin/ai-filter/{filterId}/versions`.
3. **Interactive playground** — dashboard `src/modules/ai/AiPlaygroundPage.tsx`: split-pane editor (prompt on left, test request builder on right). "Run Test" button calls existing `POST /api/v1/admin/ai-filter/test-policy` with the draft prompt. Response shows verdict, confidence, and reasoning.
4. **Accuracy scoring** — audit-service computes accuracy metrics per prompt version: `(correct decisions / total decisions)` where "correct" is determined by operator-submitted ground-truth labels (`/api/v1/admin/ai-filter/decisions/{id}/label` — CORRECT/INCORRECT/UNCLEAR).
5. **A/B testing** — `AiGatewayFilterFactory` supports a `promptVersionSplit` config parameter: `{ "v3": 90, "v4": 10 }`. The gateway probabilistically selects a prompt version per request and tags the decision with the version ID for audit analysis.
6. **Comparison dashboard** — side-by-side accuracy/latency/block-rate charts for two prompt versions. "Promote" button makes a version ACTIVE.

---

## 5. GraphQL Analytics API

**Design doc:** [`initiatives/13-graphql-analytics.md`](./initiatives/13-graphql-analytics.md)

### Description
The current analytics endpoints (`/api/v1/admin/stats`, route-level request stats, AI filter stats) are fixed-schema REST endpoints. Consumers needing custom aggregations (e.g., "error rate by route and hour for the last 7 days, filtered by status code 5xx") must make multiple calls and aggregate client-side. This initiative adds a GraphQL analytics API alongside the existing REST endpoints.

### Expected Impact
- **Flexible querying** — external dashboards (Grafana, custom UIs) fetch exactly the data they need in a single request.
- **Reduced backend round-trips** — composable queries replace N+1 REST calls for complex analytics views.
- **Future API extensibility** — new analytics dimensions are added as schema fields without versioning the REST API.

### High-Level Implementation Plan
1. **GraphQL dependency** — add `spring-boot-starter-graphql` to `routify-admin-api/pom.xml`.
2. **Schema definition** — `src/main/resources/graphql/schema.graphqls` with types: `RouteAnalytics`, `TenantUsage`, `AiFilterAnalytics`, `AuditTimeline`, `CertExpiryReport`.
3. **DataFetcher implementation** — `AnalyticsGraphQLController` with `@QueryMapping` methods that delegate to existing `AuditMessagingClient`, `RouteServiceClient`, and `DashboardStatsService` for data.
4. **Time-series support** — `RouteAnalytics` type includes `timeSeries(granularity: HOUR | DAY | WEEK)` field returning bucketed metrics.
5. **Authorization** — GraphQL queries enforce the same permission model as REST (JWT + granular RBAC from Q3).
6. **Admin-api exposure** — mounted at `/api/v1/admin/graphql`. GraphiQL playground enabled in dev profile.
7. **Dashboard integration** — selected dashboard components (route health heatmap, SLO dashboard) optionally switch to GraphQL data source for complex queries.

---

## 6. Canary Routing & Traffic Splitting

**Design doc:** [`initiatives/14-canary-routing.md`](./initiatives/14-canary-routing.md)

### Description
Build on Route Promotion Environments (Q3 Initiative 02) to add weighted traffic splitting between route versions. Operators deploy a canary route that receives a configurable percentage of traffic, with automatic rollback if error rate exceeds a threshold.

### Expected Impact
- **Progressive delivery** — new route configurations soak with 5% traffic before full rollout.
- **Automatic safety net** — error rate monitoring triggers instant rollback without operator intervention.
- **Reduces blast radius** — production incidents from bad route changes affect only a small traffic slice.

### High-Level Implementation Plan
1. **Traffic weight model** — add `trafficWeight` (0–100, default 100) and `canaryRouteId` (nullable FK to sibling route) columns to `route` table. Flyway migration.
2. **Gateway weighted routing** — `RouteDefinitionBuilder` generates a `Weight` predicate group for routes sharing the same path pattern but different weights. Spring Cloud Gateway's built-in `WeightRoutePredicateFactory` handles probabilistic selection.
3. **Canary deployment command** — `CommandEvent.DeployCanary(routeId, canaryUpstreamUri, trafficWeight, autoRollbackThreshold)`. Route-service creates a sibling route with the canary upstream and specified weight.
4. **Auto-rollback monitor** — admin-api `CanaryMonitorScheduler` polls audit-service every 30s for the canary route's error rate. If `errorRate > autoRollbackThreshold` for 3 consecutive windows, publish `CommandEvent.RollbackCanary` → route-service deletes canary, restores primary to weight=100.
5. **Admin-api endpoints** — `POST /api/v1/admin/routes/{id}/canary` (deploy), `POST /api/v1/admin/routes/{id}/canary/promote` (canary → primary), `POST /api/v1/admin/routes/{id}/canary/rollback` (manual rollback), `GET /api/v1/admin/routes/{id}/canary/status`.
6. **Dashboard** — canary badge on route cards, traffic split slider, live error rate comparison chart (primary vs canary), promote/rollback buttons.

---

## 7. Platform Alerting Engine

**Design doc:** [`initiatives/15-alerting-engine.md`](./initiatives/15-alerting-engine.md)

### Description
Build a native alerting system that evaluates threshold rules against platform metrics and delivers notifications via the webhook infrastructure (Q3 Initiative 03). Operators define alert rules through the dashboard instead of managing Prometheus alerting rules manually.

### Expected Impact
- **Self-contained alerting** — operators who don't have Prometheus/Grafana expertise can set up alerts in the dashboard.
- **SLO-aware alerts** — error budget consumption alerts bridge the gap between Q3 SLO tracking and actionable notifications.
- **Unified notification channel** — alerts flow through the same webhook pipeline as platform events, reducing integration surface.

### High-Level Implementation Plan
1. **Alert rule model** — `alert_rule` table in `routify_audit` schema: `id`, `tenantId`, `name`, `metric` (enum: `ERROR_RATE`, `P99_LATENCY`, `DLQ_DEPTH`, `CERT_EXPIRY_DAYS`, `QUOTA_USAGE`, `SLO_BUDGET`), `routeId` (optional), `operator` (GT/LT/EQ), `threshold`, `windowMinutes`, `cooldownMinutes`, `severity` (INFO/WARNING/CRITICAL), `enabled`.
2. **Evaluation scheduler** — `AlertEvaluationScheduler` in audit-service runs every 60s. For each enabled rule, queries the relevant data source (request_log aggregates, DLQ counts, cert expiry gauges) and compares against threshold.
3. **Alert state machine** — states: `OK` → `PENDING` (threshold breached, within window) → `FIRING` (breached for full window duration) → `OK` (resolved). State transitions persisted in `alert_event` table.
4. **Notification dispatch** — when an alert transitions to `FIRING`, publish to `KafkaTopics.AUDIT_EVENTS` with type `ALERT_FIRED`. The webhook dispatch consumer (Q3) delivers to subscribed webhooks with event type `ALERT_FIRED` / `ALERT_RESOLVED`.
5. **Admin-api endpoints** — CRUD at `/api/v1/admin/alerts`. `GET /api/v1/admin/alerts/{id}/history` returns state transition history.
6. **Dashboard module** — `src/modules/alerts/AlertsPage.tsx` with rule list, create/edit form (metric selector, threshold input, route picker), alert history timeline, mute/unmute toggle.
7. **SLO integration** — pre-built rule templates: "Error budget consumed > 80%", "P99 latency exceeds SLO target", "Certificate expires within 7 days".

---

## 8. Helm Chart & Kubernetes-Native Deployment

**Design doc:** [`initiatives/16-helm-chart.md`](./initiatives/16-helm-chart.md)

### Description
Package the entire Routify platform as a production-ready Helm chart for Kubernetes deployment. The current Docker Compose stack is development-only; production users need proper resource limits, health probes, HPA autoscaling, PDB disruption budgets, and Kubernetes-native secret management.

### Expected Impact
- **Production-ready from day one** — `helm install routify` deploys the full platform with sane defaults.
- **Operational maturity** — HPA auto-scales gateway instances under load; PDBs prevent total availability loss during rolling updates.
- **Secret management** — integrates with Kubernetes Secrets and external secret operators (e.g., External Secrets Operator for Vault/AWS SM).

### High-Level Implementation Plan
1. **Chart structure** — `deploy/helm/routify/` with sub-charts for each service: `gateway`, `admin-api`, `identity-service`, `route-service`, `audit-service`, `cert-vault`, `ai-service`, `dashboard`, `gitops-agent`.
2. **Values schema** — `values.yaml` with sections: `global` (image registry, pull secrets, domain), per-service blocks (`replicas`, `resources`, `env`, `persistence`), infrastructure toggles (`postgresql.enabled`, `redis.enabled` — for external-managed infra).
3. **Gateway HPA** — `HorizontalPodAutoscaler` targeting `routify.gateway.requests.total` custom metric (via Prometheus Adapter) with min=2, max=10, target=70% CPU.
4. **PodDisruptionBudgets** — `minAvailable: 1` for all services; `minAvailable: 2` for gateway.
5. **Init containers** — Flyway migrations run as init containers on identity-service, route-service, audit-service, and cert-vault pods, ensuring schema readiness before the main container starts.
6. **ConfigMaps & Secrets** — gateway config (CORS, security headers) as ConfigMap; JWT keys, DB passwords, encryption keys as Kubernetes Secrets. Document integration with External Secrets Operator.
7. **Ingress** — optional Ingress resource for admin-api + dashboard. Gateway uses its own Service (LoadBalancer or NodePort) as it *is* the ingress for API consumers.
8. **Health probes** — liveness at `/actuator/health/liveness`, readiness at `/actuator/health/readiness` (Spring Boot's Kubernetes probes group).
9. **Monitoring integration** — ServiceMonitor CRDs for Prometheus Operator, pointing at actuator management ports (9080–9086).
10. **CI integration** — `helm lint`, `helm template --validate`, and `helm test` added to CI pipeline. Smoke test deploys to a Kind cluster.

---

## Dependency & Sequencing Map

```
Week 1   Week 2   Week 3   Week 4   Week 5   Week 6   Week 7   Week 8   Week 9   Week 10  Week 11  Week 12
──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ──────── ────────
█████████████████████████████████████                                                                1. GitOps Agent
█████████████████████████████████████                                                                2. Tenant Quotas
                  ████████████████████████████████████                                                3. Multi-GW Cluster
                           ████████████████████████████████████████                                   4. AI Playground
                                    █████████████████████████████████████████                         5. GraphQL Analytics
                                             █████████████████████████████████████████                6. Canary Routing
                                                                       █████████████████████████████  7. Alerting Engine
                                                              █████████████████████████████████████  8. Helm Chart
```

### Key Dependencies (Q4 internal + Q3 prerequisites)

| Initiative | Depends On |
|-----------|------------|
| 1. GitOps Agent | Q3-07 (Import/Export), Q3-01 (API Keys for agent auth), Q3-03 (Webhooks for reporting) |
| 2. Tenant Quotas | Existing `TenantPlan` enum, `TenantContextGatewayFilterFactory`, `RedisKeys` |
| 3. Multi-GW Cluster | Existing gateway Kafka consumers, Redis infrastructure |
| 4. AI Playground | Existing `AiFilterEvaluationService`, Q3-05 (Health Dashboard for metrics charts) |
| 5. GraphQL Analytics | Q3-05 (Health Dashboard backend stats), existing audit-service query handlers |
| 6. Canary Routing | Q3-02 (Route Promotion Environments), Q3-05 (SLO for rollback thresholds) |
| 7. Alerting Engine | Q3-03 (Webhooks for delivery), Q3-05 (SLO model for alert templates) |
| 8. Helm Chart | All services stable; Q4-03 (Multi-GW) for gateway HPA patterns |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| GitOps agent polling creates excessive admin-api load | Low | Medium | SHA-256 short-circuit skips import when YAML unchanged; configurable poll interval (60s–300s) |
| Redis request quota counter hotspot under high traffic | Medium | High | Use Redis `INCRBY` pipeline batching; shard by `{tenantId}:{gatewayInstanceId}` and merge reads |
| Weighted routing accuracy at low traffic volumes | Medium | Low | Document that weight precision improves above ~100 req/min; at low traffic, use environment promotion instead |
| Helm chart complexity explosion with 9 sub-charts | Medium | Medium | Use Helmfile for umbrella orchestration; keep sub-charts independently installable |
| GraphQL N+1 query performance | Low | Medium | Use DataLoader batching for RabbitMQ RPC calls; add query depth limiting (max depth=5) |
| Canary auto-rollback false positives from noisy metrics | Medium | High | Require 3 consecutive breached windows before rollback; add manual override to suppress auto-rollback |

---

## Success Metrics

| Metric | Baseline (Q3 End) | Target (Q4 End) |
|--------|-------------------|-----------------|
| Time from Git commit to live gateway config | N/A (manual import) | < 90s (GitOps agent) |
| Tenant plan quota enforcement coverage | 0% (not enforced) | 100% (routes, filters, requests) |
| Gateway instances with consistent config version | Unknown | 100% within 30s of config change |
| AI prompt iteration cycle time | ~1 hour (manual test) | < 5 min (playground + versioning) |
| Production incidents from route misconfig | ~3/month (estimate) | < 1/month (canary + auto-rollback) |
| Time to deploy full platform on Kubernetes | N/A (Docker Compose only) | < 15 min (`helm install`) |
| Alert rule coverage for SLO violations | 0 (manual Grafana alerts) | ≥1 alert per active SLO |

