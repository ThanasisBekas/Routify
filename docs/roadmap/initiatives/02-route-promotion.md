# Initiative 02 — Route Promotion Environments

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 2–5 · **Owner:** Route-service + Gateway teams

---

## Problem Statement

All route changes go live immediately after activation (`ACTIVE` status). There is no way to validate a route configuration change against real-ish traffic before it hits production consumers. Misconfigured upstream URIs, broken filter chains, or invalid path patterns cause production incidents that could be caught in a staging context.

## Solution Overview

Add a lightweight `environment` dimension to routes. Routes are tagged `STAGING` or `PRODUCTION`. The gateway loads both but only matches staging routes when the request carries an explicit `X-Route-Environment: STAGING` header (or a configurable path prefix `/staging/`). Promotion copies staging config to the production version atomically.

```
┌── Route: /api/orders ──────────────────────────────────┐
│  version 5 (PRODUCTION) — live traffic                  │
│  version 6 (STAGING)    — only X-Route-Environment      │
│                                                          │
│  Promote v6 → v7 (PRODUCTION): v6 config → v7, v6 deleted │
└──────────────────────────────────────────────────────────┘
```

---

## Detailed Implementation Steps

### Step 1: Domain Model Extension (route-service)

**Files to modify:**
- `routify-route-service/src/main/resources/db/migration/V{next}__route_environment.sql`
- `routify-route-service/.../domain/Route.java`

**Migration:**
```sql
-- Add environment column with default PRODUCTION (non-breaking)
ALTER TABLE routify.route
    ADD COLUMN environment VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION';

-- Index for gateway snapshot query filtering
CREATE INDEX idx_route_environment ON routify.route(tenant_id, environment, status);
```

**Domain enum (routify-common):**
```java
// routify-common/.../domain/RouteEnvironment.java
public enum RouteEnvironment {
    STAGING,
    PRODUCTION
}
```

**Task list:**
- [ ] Create Flyway migration
- [ ] Create `RouteEnvironment` enum in `routify-common`
- [ ] Add `environment` field to `Route` entity
- [ ] Update `RouteMapper` (MapStruct) to include environment
- [ ] Update `RouteSummary` / `RouteDto` DTOs

---

### Step 2: Command Events (routify-common)

**Files to modify:**
- `routify-common/.../event/CommandEvent.java`

**New command record:**
```java
record PromoteRoute(UUID commandId, UUID tenantId, UUID routeId,
    String actor) implements CommandEvent {}
```

**Modify existing commands:**
```java
// CreateRoute and UpdateRoute gain optional 'environment' field:
record CreateRoute(UUID commandId, UUID tenantId, String name,
    String pathPattern, String methods, String upstreamUri,
    String stripPrefix, String description,
    Map<String, Object> extraConfig, RouteEnvironment environment,
    String actor) implements CommandEvent {}
```

**Task list:**
- [ ] Add `PromoteRoute` command record
- [ ] Add `environment` field to `CreateRoute` (default `PRODUCTION` for backward compat)
- [ ] Update TypeScript types (`RouteDto`, `CreateRouteRequest`)

---

### Step 3: Route-Service Promotion Handler

**Files to modify:**
- `routify-route-service/.../service/RouteService.java`
- `routify-route-service/.../messaging/RouteCommandConsumer.java`

**Promotion logic:**
1. Load staging route by `routeId` — fail if not `environment=STAGING` or not `status=ACTIVE`.
2. Find or create matching production route (same `name` + `tenantId`).
3. Copy all config fields (pathPattern, methods, upstreamUri, stripPrefix, extraConfig, filters) from staging → production.
4. Increment production route version.
5. Set staging route `status=ARCHIVED`.
6. Publish `DomainEvent.RoutePromoted` with both staging and production snapshots (for audit diff).
7. Publish `ROUTE_EVENTS` so the gateway hot-reloads.

**Task list:**
- [ ] Implement `promoteRoute()` in `RouteService`
- [ ] Add `PromoteRoute` case to `RouteCommandConsumer` switch
- [ ] Create `DomainEvent.RoutePromoted` event record
- [ ] Add promotion snapshot to outbox

---

### Step 4: Gateway Environment-Aware Routing

**Files to modify:**
- `routify-api-gateway/.../routing/RouteDefinitionBuilder.java`
- `routify-api-gateway/.../config/GatewayConfig.java`

**Predicate injection:**
```java
// In RouteDefinitionBuilder, when building predicates:
if (route.getEnvironment() == RouteEnvironment.STAGING) {
    // Only match when X-Route-Environment: STAGING header present
    predicates.add(new PredicateDefinition(
        "Header=X-Route-Environment, STAGING"));
}
```

**Configuration property:**
```yaml
routify:
  gateway:
    staging:
      enabled: true
      header-name: X-Route-Environment  # configurable
```

**Task list:**
- [ ] Add staging predicate to `RouteDefinitionBuilder`
- [ ] Add staging config properties
- [ ] Verify production routes remain unaffected (no extra predicate)
- [ ] Test that staging routes are invisible to normal requests

---

### Step 5: Admin-API Endpoint

**Files to modify:**
- `routify-admin-api/.../controller/AdminRoutesController.java`

**New endpoint:**
```
POST /api/v1/admin/routes/{id}/promote
Authorization: SUPER_ADMIN, TENANT_ADMIN
Response: 202 AsyncAcknowledgement
```

**Modify existing endpoints:**
- `GET /api/v1/admin/routes` — add `?environment=STAGING|PRODUCTION` query param filter.
- `POST /api/v1/admin/routes` — accept optional `environment` field in request body.

**Task list:**
- [ ] Add `promote` endpoint to `AdminRoutesController`
- [ ] Add `environment` filter to routes list query
- [ ] Update `RouteServiceClient` Kafka command publisher

---

### Step 6: Dashboard UI

**Files to modify:**
- `routify-dashboard/src/modules/routes/RouteFormModal.tsx` — add environment selector
- `routify-dashboard/src/modules/routes/` — add promote button + diff modal
- `routify-dashboard/src/types/index.ts` — add `RouteEnvironment` type
- `routify-dashboard/src/api/routesApi.ts` — add promote API call

**UX details:**
- Route cards show environment badge: 🟢 PRODUCTION / 🟡 STAGING.
- Environment toggle filter in route list header.
- "Promote to Production" button (only on STAGING + ACTIVE routes) opens a side-by-side diff modal showing current production config vs staging config.
- After promotion, toast: "Route promoted to production. Gateway will reload within seconds."

**Task list:**
- [ ] Add `RouteEnvironment` type to `src/types/index.ts`
- [ ] Add environment badge component
- [ ] Add environment filter toggle to routes page
- [ ] Create promotion diff modal component
- [ ] Add promote API function
- [ ] Wire promote action to UI
- [ ] Add MSW mock handlers
- [ ] Update E2E test `routes.spec.ts`

---

## Acceptance Criteria

- [ ] Creating a route with `environment: STAGING` makes it accessible only via `X-Route-Environment: STAGING`
- [ ] Normal requests (no header) never match staging routes
- [ ] Promoting a staging route updates the production route atomically and archives the staging version
- [ ] The gateway hot-reloads within the existing Kafka event pipeline (no new topics needed)
- [ ] Audit log captures the promotion with before/after config diff
- [ ] Dashboard shows environment badges and the promote flow with diff preview

---

## Rollback Plan

The `environment` column defaults to `PRODUCTION`, so all existing routes continue to work. If the feature is problematic:
1. Disable staging predicate in gateway config: `routify.gateway.staging.enabled: false`
2. All staging routes remain in DB but are never matched — no traffic impact

