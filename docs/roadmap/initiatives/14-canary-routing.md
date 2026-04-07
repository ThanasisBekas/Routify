# Initiative 14 — Canary Routing & Traffic Splitting

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 6–9 · **Owner:** Route-service + Gateway teams  
> **Prerequisites:** Q3-02 (Route Promotion Environments), Q3-05 (SLO model for rollback thresholds)

---

## Problem Statement

Route changes go from staging to production as an all-or-nothing promotion (Q3). There is no way to gradually roll out a new upstream URI or filter chain to a small percentage of traffic and monitor impact before full deployment. A misconfigured upstream that passes staging validation can still fail under production load patterns.

## Solution Overview

Add weighted traffic splitting between route versions. A "canary" route receives a configurable percentage of traffic for the same path pattern. An auto-rollback monitor watches the canary's error rate and reverts to the primary if a threshold is breached.

---

## Detailed Implementation Steps

### Step 1: Domain Model Extension (route-service)

**Files to modify:**
- `routify-route-service/src/main/resources/db/migration/V{next}__canary_routing.sql`
- `routify-route-service/.../domain/Route.java`

**Migration:**
```sql
ALTER TABLE routify.route
    ADD COLUMN traffic_weight    INT NOT NULL DEFAULT 100,
    ADD COLUMN canary_route_id   UUID REFERENCES routify.route(id) ON DELETE SET NULL,
    ADD COLUMN canary_auto_rollback_threshold DECIMAL(5,2);  -- error rate % threshold

-- Constraint: traffic_weight between 0 and 100
ALTER TABLE routify.route ADD CONSTRAINT chk_traffic_weight
    CHECK (traffic_weight >= 0 AND traffic_weight <= 100);
```

**Task list:**
- [x] Create Flyway migration
- [x] Add fields to `Route` entity
- [x] Update `RouteMapper` and DTOs
- [x] Update TypeScript `RouteDto` type

---

### Step 2: Command Events (routify-common)

**New command records in `CommandEvent`:**
```java
record DeployCanary(UUID commandId, UUID tenantId, UUID routeId,
    String canaryUpstreamUri, int trafficWeight,
    double autoRollbackThreshold,
    Map<String, Object> canaryExtraConfig,
    String actor) implements CommandEvent {}

record PromoteCanary(UUID commandId, UUID tenantId, UUID routeId,
    String actor) implements CommandEvent {}

record RollbackCanary(UUID commandId, UUID tenantId, UUID routeId,
    String reason, String actor) implements CommandEvent {}

record AdjustCanaryWeight(UUID commandId, UUID tenantId, UUID routeId,
    int newWeight, String actor) implements CommandEvent {}
```

**Task list:**
- [x] Add command records to `CommandEvent`
- [x] Add TypeScript request types

---

### Step 3: Route-Service Canary Handlers

**Files to modify:**
- `routify-route-service/.../service/RouteService.java`
- `routify-route-service/.../messaging/RouteCommandConsumer.java`

**Deploy canary:**
1. Validate primary route exists and is `ACTIVE` + `PRODUCTION`.
2. Create a sibling route: same `name` + `-canary` suffix, same `pathPattern`, `methods`, and filter chain, but with `canaryUpstreamUri` as upstream and `trafficWeight` as specified.
3. Set primary route's `trafficWeight = 100 - canaryWeight`.
4. Set primary's `canaryRouteId = canary.id`.
5. Publish both route changes via `ROUTE_EVENTS`.

**Promote canary:**
1. Copy canary's upstream/config → primary route.
2. Set primary `trafficWeight = 100`, clear `canaryRouteId`.
3. Delete (or archive) canary route.
4. Publish route events.

**Rollback canary:**
1. Set primary `trafficWeight = 100`, clear `canaryRouteId`.
2. Set canary `status = ARCHIVED`.
3. Publish route events.

**Adjust weight:**
1. Update canary's `trafficWeight` to new value.
2. Update primary's `trafficWeight` to `100 - newValue`.
3. Publish route events.

**Task list:**
- [x] Implement `deployCanary()` in `RouteService`
- [x] Implement `promoteCanary()` 
- [x] Implement `rollbackCanary()`
- [x] Implement `adjustCanaryWeight()`
- [x] Add all cases to `RouteCommandConsumer` switch
- [x] Publish appropriate domain events and outbox entries

---

### Step 4: Gateway Weighted Routing

**Files to modify:**
- `routify-api-gateway/.../routing/RouteDefinitionBuilder.java`

**Predicate injection:**
When building route definitions, if a route has `trafficWeight < 100` and shares a path pattern group:
```java
// Spring Cloud Gateway built-in Weight predicate:
String weightGroup = "canary-" + primaryRouteId;
predicates.add(new PredicateDefinition(
    "Weight=" + weightGroup + ", " + route.getTrafficWeight()));
```

Both the primary (e.g., weight=90) and canary (e.g., weight=10) routes get `Weight` predicates in the same group. The gateway's `WeightRoutePredicateFactory` handles probabilistic selection.

**Task list:**
- [x] Add weight predicate to `RouteDefinitionBuilder` when `trafficWeight < 100`
- [x] Group primary and canary routes by shared path pattern
- [x] Verify weight predicates work with existing route predicates (path, method, headers)
- [x] Test weighted distribution accuracy

---

### Step 5: Auto-Rollback Monitor (admin-api)

**Files to create:**
- `routify-admin-api/.../service/CanaryMonitorService.java`

**Scheduler:** Runs every 30s. For each active canary:
1. Query audit-service for canary route's error rate in the last 5 minutes.
2. Compare against `canaryAutoRollbackThreshold`.
3. If exceeded for 3 consecutive checks (90s total) → publish `CommandEvent.RollbackCanary` with reason "Auto-rollback: error rate {actual}% exceeded threshold {threshold}%".
4. Fire `CANARY_ROLLBACK` webhook event.

**State tracking:** `ConcurrentHashMap<UUID, Integer>` tracking consecutive breach count per canary. Reset on any non-breach check.

**Task list:**
- [x] Create monitor service
- [x] Implement error rate query to audit-service
- [x] Implement 3-consecutive-breach rule
- [x] Publish rollback command on breach
- [x] Add `CANARY_DEPLOYED`, `CANARY_PROMOTED`, `CANARY_ROLLBACK` to `WebhookEventType`
- [x] Add metrics: `routify.canary.deployments` counter, `routify.canary.rollbacks` counter

---

### Step 6: Admin-API Endpoints

**Endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/routes/{id}/canary` | Deploy canary |
| `GET` | `/api/v1/admin/routes/{id}/canary/status` | Canary health + error rate comparison |
| `POST` | `/api/v1/admin/routes/{id}/canary/promote` | Promote canary → primary |
| `POST` | `/api/v1/admin/routes/{id}/canary/rollback` | Manual rollback |
| `PUT` | `/api/v1/admin/routes/{id}/canary/weight` | Adjust traffic weight |

**Canary status response:**
```json
{
  "routeId": "...",
  "canaryRouteId": "...",
  "primaryWeight": 90,
  "canaryWeight": 10,
  "canaryUpstreamUri": "http://orders-v2:8080",
  "autoRollbackThreshold": 5.0,
  "primaryErrorRate": 0.3,
  "canaryErrorRate": 1.2,
  "deployedAt": "2026-08-10T14:00:00Z",
  "breachCount": 0
}
```

**Task list:**
- [x] Create endpoints in `AdminRoutesController` (or new `AdminCanaryController`)
- [x] Wire to Kafka commands and RabbitMQ queries

---

### Step 7: Dashboard UI

**Files to create:**
- `routify-dashboard/src/modules/routes/CanaryDeployModal.tsx`
- `routify-dashboard/src/modules/routes/CanaryStatusPanel.tsx`
- `routify-dashboard/src/modules/routes/components/TrafficWeightSlider.tsx`

**UX:**
- "Deploy Canary" button on active production routes → opens modal: upstream URI input, traffic weight slider (5–50%), auto-rollback threshold input.
- Canary status panel visible when a canary is active: dual progress bars (primary vs canary traffic), live error rate comparison chart (Recharts), weight adjustment slider, promote/rollback buttons.
- Canary badge on route card in list view.

**Task list:**
- [x] Create canary deploy modal
- [x] Create canary status panel with error rate chart
- [x] Create traffic weight slider component
- [x] Wire promote/rollback/adjust actions
- [x] Add MSW mock handlers

---

## Acceptance Criteria

- [x] Deploying a canary splits traffic 90/10 between primary and canary upstreams
- [x] Adjusting weight to 70/30 takes effect within one gateway reload cycle
- [x] Canary with error rate above threshold for 90s is automatically rolled back
- [x] Promoting a canary makes the canary upstream the new primary and removes the canary
- [x] Dashboard shows live error rate comparison between primary and canary
- [x] Canary deployment/promotion/rollback events appear in audit log and trigger webhooks

