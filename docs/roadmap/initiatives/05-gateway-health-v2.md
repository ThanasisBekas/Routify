# Initiative 05 — Gateway Health Dashboard v2

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 5–8 · **Owner:** Admin-API + Dashboard teams

---

## Problem Statement

The current gateway page (`src/modules/gateway/GatewayPage.tsx`) shows basic health/routes/circuit-breaker status from the live gateway RPC (`QUEUE_GATEWAY_STATUS`). Operators must switch to Grafana for latency analysis, error rate trends, and trace correlation. There's no SLO tracking or error budget visibility in the dashboard.

## Solution Overview

Redesign the gateway module into a multi-tab health dashboard with: (1) per-route latency heatmap, (2) SLO status with error budget bars, (3) circuit-breaker cards with live state from WebSocket, (4) direct trace link-out to Grafana Tempo. Data comes from existing `RoutifyMetrics` counters/timers via Prometheus and audit-service request stats via RabbitMQ.

---

## Detailed Implementation Steps

### Step 1: Route Health Stats Backend (audit-service)

**Files to modify:**
- `routify-audit-service/.../messaging/` — add new RabbitMQ handler

**New RabbitMQ query:**
```java
// RabbitTopology additions:
public static final String QUEUE_AUDIT_ROUTE_HEALTH = "routify.audit-service.route.health";
public static final String RK_AUDIT_ROUTE_HEALTH    = "audit.route.health";
```

**Query request:**
```java
record RouteHealthQuery(UUID tenantId, String window) implements QueryRequest {}
// window: "1h" | "24h" | "7d"
```

**Response:**
```java
record RouteHealthResponse(List<RouteHealthEntry> routes) implements QueryResponse {}
record RouteHealthEntry(
    UUID routeId, String routeName,
    long totalRequests, long errorCount,
    double errorRate,           // errorCount / totalRequests
    double p50LatencyMs, double p95LatencyMs, double p99LatencyMs,
    double avgLatencyMs,
    Map<Integer, Long> statusCodeDistribution  // { 200: 1500, 404: 23, 500: 7 }
) {}
```

**Implementation:** Query `request_log` table aggregated by `route_id` within the time window.

**Task list:**
- [ ] Add `RabbitTopology` constants
- [ ] Add `QueryRequest.RouteHealthQuery` / `QueryResponse.RouteHealthResponse`
- [ ] Implement query handler in audit-service (SQL aggregation)
- [ ] Add index on `request_log(route_id, requested_at)` if not present

---

### Step 2: SLO Configuration Model (route-service)

**Files to create:**
- `routify-route-service/src/main/resources/db/migration/V{next}__route_slo.sql`
- `routify-route-service/.../domain/RouteSlo.java`
- `routify-route-service/.../repository/RouteSloRepository.java`

**Schema:**
```sql
CREATE TABLE routify.route_slo (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id                UUID NOT NULL UNIQUE REFERENCES routify.route(id) ON DELETE CASCADE,
    availability_target     DECIMAL(5,2) NOT NULL DEFAULT 99.90,  -- percentage
    latency_p99_target_ms   INT NOT NULL DEFAULT 1000,
    evaluation_window_hours INT NOT NULL DEFAULT 168,              -- 7 days
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**RabbitMQ queries:**
```java
QUEUE_ROUTE_SLO_GET  = "routify.route-service.route-slo.get";
QUEUE_ROUTE_SLO_SAVE = "routify.route-service.route-slo.save";
```

**Task list:**
- [ ] Create Flyway migration
- [ ] Create `RouteSlo` entity
- [ ] Create repository
- [ ] Add RabbitMQ get/save handlers
- [ ] Add topology constants

---

### Step 3: Error Budget Calculation (admin-api)

**Files to modify:**
- `routify-admin-api/.../service/DashboardStatsService.java`

**Endpoint:**
```
GET /api/v1/admin/routes/{id}/slo-status
```

**Response:**
```json
{
  "routeId": "...",
  "slo": { "availabilityTarget": 99.9, "latencyP99TargetMs": 1000, "windowHours": 168 },
  "actual": {
    "availability": 99.82,
    "latencyP99Ms": 850,
    "totalRequests": 142000,
    "errorCount": 256
  },
  "errorBudget": {
    "totalBudget": 142,        // 142000 * (1 - 99.9/100)
    "consumed": 256,
    "remaining": -114,          // negative = budget exceeded
    "percentConsumed": 180.3
  },
  "latencySloMet": true,
  "availabilitySloMet": false
}
```

**Computation:**
```java
double allowedErrors = totalRequests * (1 - availabilityTarget / 100.0);
double consumed = errorCount;
double remaining = allowedErrors - consumed;
double percentConsumed = (consumed / allowedErrors) * 100;
```

**Task list:**
- [ ] Add `getRouteSloStatus(tenantId, routeId)` to `DashboardStatsService`
- [ ] Query route SLO config via RabbitMQ from route-service
- [ ] Query actual metrics via RabbitMQ from audit-service
- [ ] Compute error budget
- [ ] Add REST endpoint to controller

---

### Step 4: Dashboard Redesign — Overview Tab

**Files to modify/create:**
- `routify-dashboard/src/modules/gateway/GatewayPage.tsx` — refactor into tab layout
- `routify-dashboard/src/modules/gateway/tabs/OverviewTab.tsx`
- `routify-dashboard/src/modules/gateway/tabs/RoutesHealthTab.tsx`
- `routify-dashboard/src/modules/gateway/tabs/CircuitBreakersTab.tsx`
- `routify-dashboard/src/modules/gateway/tabs/SloTab.tsx`
- `routify-dashboard/src/api/gatewayApi.ts` — add new API functions

**Overview Tab content:**
- Summary cards: Total routes (active/disabled), Total requests (24h), Global error rate, Gateway uptime.
- Mini sparklines for request volume trend (24h, from existing `/api/v1/admin/stats`).
- Live status indicator from WebSocket (`wsStore`).

**Task list:**
- [ ] Refactor `GatewayPage` into tab layout
- [ ] Create `OverviewTab` component
- [ ] Source data from existing `DashboardStatsService`

---

### Step 5: Dashboard — Routes Health Tab (Heatmap)

**Components:**
- `RoutesHealthTab.tsx` — container with time window selector (1h/24h/7d)
- `LatencyHeatmap.tsx` — Recharts `<HeatMapGrid>` or custom SVG: X-axis = time buckets, Y-axis = routes, color intensity = p99 latency
- `RouteHealthRow.tsx` — individual route row with sparkline, error rate bar, status code breakdown

**Data flow:**
```
gatewayApi.getRouteHealth(tenantId, window) → admin-api → RabbitMQ → audit-service
```

**Task list:**
- [ ] Create `RoutesHealthTab` with time window selector
- [ ] Create `LatencyHeatmap` component (Recharts or custom)
- [ ] Create per-route health row with sparklines
- [ ] Add `getRouteHealth()` to `gatewayApi.ts`
- [ ] Add TypeScript types for health response
- [ ] Use `useRealtimeQuery` hook for auto-refresh

---

### Step 6: Dashboard — SLO Tab

**Components:**
- `SloTab.tsx` — list of routes with SLO config and status
- `ErrorBudgetBar.tsx` — horizontal bar: green (budget remaining) / red (budget exceeded)
- `SloConfigModal.tsx` — edit SLO targets per route

**UX:**
- Each route shows: availability target, actual availability, error budget bar, latency SLO status (✅/❌).
- Click to edit SLO targets.
- Routes exceeding budget are highlighted in red.

**Task list:**
- [ ] Create `SloTab` component
- [ ] Create `ErrorBudgetBar` component
- [ ] Create `SloConfigModal` (React Hook Form + Zod)
- [ ] Add SLO API functions to `gatewayApi.ts`
- [ ] Wire save action → Kafka command via admin-api

---

### Step 7: Dashboard — Circuit Breakers Tab (Live)

**Enhancement of existing circuit breaker display:**
- Cards per route showing CB state: CLOSED (green), OPEN (red), HALF_OPEN (amber).
- Live transitions via WebSocket (`/topic/metrics` payloads already include CB state in `wsStore`).
- State transition timeline (last 10 transitions).

**Task list:**
- [ ] Create `CircuitBreakersTab` component
- [ ] Use `wsStore` circuit breaker state for live updates
- [ ] Add state transition history (stored in Zustand, last 10 per CB)

---

### Step 8: Tempo Trace Link-Out

**Files to modify:**
- `routify-dashboard/src/modules/audit/AuditDetailModal.tsx`
- `routify-dashboard/src/modules/gateway/tabs/RoutesHealthTab.tsx`

**Implementation:**
- Add "View Trace" icon button next to any request log entry that has a `correlationId`.
- Link format: `${GRAFANA_BASE_URL}/explore?left={"datasource":"Tempo","queries":[{"queryType":"traceql","query":"${correlationId}"}]}`
- `GRAFANA_BASE_URL` configurable via `VITE_GRAFANA_URL` env var (default `http://localhost:3001`).

**Task list:**
- [ ] Add `VITE_GRAFANA_URL` env var
- [ ] Create `TraceLink` component
- [ ] Add to request log rows in audit module
- [ ] Add to route health rows

---

## Acceptance Criteria

- [ ] Gateway page loads with 4 tabs: Overview, Routes Health, SLOs, Circuit Breakers
- [ ] Latency heatmap displays per-route p99 latency with color coding
- [ ] SLO error budget bar accurately reflects request error rates
- [ ] Circuit breaker tab updates live via WebSocket without polling
- [ ] "View Trace" links open Grafana Tempo with the correct trace query
- [ ] Page loads within 2 seconds including the health data query

