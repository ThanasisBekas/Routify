# Initiative 10 — Tenant Quota Enforcement & Usage Analytics

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 1–4 · **Owner:** Identity-service + Gateway + Audit teams  
> **Prerequisites:** Existing `TenantPlan` enum, `TenantContextGatewayFilterFactory`, `RedisKeys`

---

## Problem Statement

`TenantPlan` defines quotas (`FREE: 10 routes / 5 filters / 1K requests`, `STARTER: 50/20/10K`, etc.) but they are not enforced anywhere. Any tenant can create unlimited resources. Monthly request quotas are not tracked. There is no usage visibility for tenant admins or super admins.

## Solution Overview

Enforce quotas at three layers: (1) service-layer on resource creation commands, (2) gateway-layer for request counting, (3) audit-layer for historical usage analytics. Deliver usage visibility through dashboard progress bars and trend charts.

---

## Detailed Implementation Steps

### Step 1: Service-Layer Quota Checks (route-service)

**Files to modify:**
- `routify-route-service/.../service/RouteService.java`
- `routify-route-service/.../service/FilterService.java` (or equivalent)

**Logic in `RouteService.createRoute()`:**
```java
long currentRouteCount = routeRepository.countByTenantIdAndStatusNot(tenantId, RouteStatus.ARCHIVED);
TenantPlan plan = tenantService.getPlanForTenant(tenantId); // RabbitMQ RPC to identity-service
if (currentRouteCount >= plan.maxRoutes()) {
    throw new RoutifyException.QuotaExceeded(
        "Route limit reached (%d/%d) for plan %s. Upgrade to create more routes."
            .formatted(currentRouteCount, plan.maxRoutes(), plan.name()));
}
```

Same pattern for filter creation against `plan.maxFilters()`.

**Tenant plan resolution:** Route-service needs the tenant's plan. Options:
- (A) Add `plan` to the Kafka command header (admin-api looks it up before publishing).
- (B) Route-service caches `tenantId → plan` from `TENANT_EVENTS` Kafka consumer (already consumes tenant lifecycle events).
- Prefer (B) for decoupling — route-service maintains a local `ConcurrentHashMap<UUID, TenantPlan>` refreshed on tenant events.

**Task list:**
- [x] Add tenant plan cache in route-service (populate from `TENANT_EVENTS`)
- [x] Add quota check in `RouteService.createRoute()`
- [x] Add quota check in filter creation logic
- [x] Throw `RoutifyException.QuotaExceeded` with plan details in error message
- [x] Add unit tests for quota boundary conditions

---

### Step 2: Gateway Request Quota Counting

**Files to modify:**
- `routify-api-gateway/.../filter/TenantContextGatewayFilterFactory.java`

**Redis counter key:** `routify:quota:{tenantId}:{YYYY-MM}` (aligns with `RedisKeys` prefix pattern).  
**Counter operation:** Atomic `INCR` on every request that has a resolved `tenantId`.  
**Quota check:**
```java
Long currentCount = redisTemplate.opsForValue()
    .increment("routify:quota:" + tenantId + ":" + YearMonth.now());
if (currentCount == 1) {
    // First request of the month — set TTL to end of month + 1 day buffer
    redisTemplate.expire(key, Duration.between(Instant.now(), endOfMonth.plusDays(1)));
}
if (currentCount > plan.monthlyRequestQuota()) {
    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    exchange.getResponse().getHeaders().set("Retry-After", secondsUntilNextMonth());
    return exchange.getResponse().setComplete();
}
```

**Plan resolution at gateway:** Gateway already consumes `TENANT_EVENTS` for tenant context. Extend to cache `tenantId → TenantPlan` mapping.

**Task list:**
- [x] Add `RedisKeys.QUOTA_PREFIX = "routify:quota:"` to `routify-common`
- [x] Implement monthly counter increment in `TenantContextGatewayFilterFactory`
- [x] Implement quota check with HTTP 429 + `Retry-After`
- [x] Cache tenant plans in gateway from `TENANT_EVENTS`
- [x] Add `routify.gateway.quota.enabled: true` feature flag
- [x] Add `routify.gateway.requests.quota_exceeded` counter to `RoutifyMetrics`

---

### Step 3: Usage Tracking (audit-service)

**Files to create:**
- `routify-audit-service/src/main/resources/db/migration/V{next}__tenant_usage.sql`
- `routify-audit-service/.../domain/TenantUsageDaily.java`
- `routify-audit-service/.../repository/TenantUsageDailyRepository.java`
- `routify-audit-service/.../scheduler/UsageSnapshotScheduler.java`

**Schema:**
```sql
CREATE TABLE routify_audit.tenant_usage_daily (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    date            DATE NOT NULL,
    route_count     INT NOT NULL DEFAULT 0,
    filter_count    INT NOT NULL DEFAULT 0,
    request_count   BIGINT NOT NULL DEFAULT 0,
    error_count     BIGINT NOT NULL DEFAULT 0,
    snapshot_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, date)
);
CREATE INDEX idx_tenant_usage_tenant_date ON routify_audit.tenant_usage_daily(tenant_id, date DESC);
```

**`UsageSnapshotScheduler`** — runs daily at 00:05 UTC:
1. For each active tenant, query `request_log` for yesterday's request count and error count.
2. Query route-service (RabbitMQ) for current route and filter counts.
3. Upsert into `tenant_usage_daily`.

**Task list:**
- [x] Create Flyway migration
- [x] Create entity and repository
- [x] Create daily snapshot scheduler
- [x] Add RabbitMQ query handler for usage data

---

### Step 4: Admin-API Usage Endpoints

**Files to create/modify:**
- `routify-admin-api/.../controller/AdminTenantsController.java` — add usage endpoints

**Endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/tenants/{id}/usage` | Current period usage vs plan limits |
| `GET` | `/api/v1/admin/tenants/{id}/usage/history?days=30` | Daily usage trend |

**Current usage response:**
```json
{
  "tenantId": "...",
  "plan": "STARTER",
  "routes": { "used": 12, "limit": 50, "percentage": 24 },
  "filters": { "used": 8, "limit": 20, "percentage": 40 },
  "requests": { "used": 4523, "limit": 10000, "percentage": 45, "periodStart": "2026-07-01", "periodEnd": "2026-07-31" }
}
```

**Task list:**
- [x] Add usage endpoint to tenants controller
- [x] Add usage history endpoint
- [x] Query Redis for current-month request count
- [x] Query route-service for route/filter counts via RabbitMQ
- [x] Return plan limits from identity-service

---

### Step 5: Dashboard Usage UI

**Files to modify:**
- `routify-dashboard/src/modules/workspaces/` — enhance existing workspace pages

**Components:**
- `UsageOverview.tsx` — three progress bars (routes, filters, requests) with color coding (green < 70%, amber 70–90%, red > 90%).
- `UsageTrendChart.tsx` — Recharts line chart showing daily request volume for last 30 days.
- `PlanComparisonTable.tsx` — side-by-side plan features (FREE/STARTER/PRO/ENTERPRISE).
- "Upgrade Plan" button (TENANT_ADMIN only) — opens upgrade request form.

**Task list:**
- [x] Create usage overview component
- [x] Create trend chart component
- [x] Add TypeScript types for usage response
- [x] Add API functions to `tenantsApi.ts`
- [x] Integrate into workspace detail page
- [x] Add MSW mock handlers

---

### Step 6: Quota Webhooks

**New webhook event types** (added to `WebhookEventType` from Q3):
```java
QUOTA_WARNING,    // 80% of any quota reached
QUOTA_EXCEEDED    // 100% of any quota reached
```

**Task list:**
- [x] Add event types to `WebhookEventType` enum
- [x] Publish events from gateway (request quota) and route-service (resource quota)
- [x] Add dashboard TypeScript types

---

## Acceptance Criteria

- [x] A FREE tenant (10 routes) receives HTTP 429 when creating an 11th route
- [x] A FREE tenant (1,000 requests/month) receives HTTP 429 with `Retry-After` when exceeding quota
- [x] Tenant usage dashboard shows accurate progress bars for all three quota dimensions
- [x] Daily usage history chart shows 30-day trend
- [x] Quota warning webhooks fire at 80% threshold
- [x] Quota checks are bypassed for ENTERPRISE plan (unlimited)
- [x] Feature flag `routify.gateway.quota.enabled` can disable request counting
