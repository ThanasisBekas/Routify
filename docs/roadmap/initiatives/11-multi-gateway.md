# Initiative 11 — Multi-Gateway Cluster Awareness

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 3–6 · **Owner:** Gateway + Admin-API teams  
> **Prerequisites:** Existing gateway Kafka consumers, Redis infrastructure

---

## Problem Statement

Running multiple `routify-api-gateway` instances works (each consumes Kafka events independently) but there is no visibility into the fleet. Operators cannot answer: "Do all instances have the latest route config?", "Which instance is lagging?", or "How many instances are running?" Config drift between instances causes inconsistent routing behavior that is invisible until users report errors.

## Solution Overview

Add a lightweight cluster registry using Redis, config version tracking, and a fleet status dashboard tab. Gateway instances heartbeat to Redis, report their config version, and admin-api provides a unified fleet view.

---

## Detailed Implementation Steps

### Step 1: Gateway Instance Registry (Redis)

**Files to create:**
- `routify-api-gateway/.../config/GatewayClusterConfig.java`
- `routify-api-gateway/.../cluster/GatewayInstanceRegistry.java`

**Redis key structure:**
```
routify:gateway:instances:{instanceId}  →  Redis Hash
  hostname: "gateway-pod-abc123"
  port: 8080
  configVersion: 47
  routeCount: 125
  filterCount: 89
  startedAt: "2026-07-15T10:30:00Z"
  lastReloadAt: "2026-07-15T14:22:05Z"
  lastHeartbeatAt: "2026-07-15T14:25:00Z"
  TTL: 30s (auto-expire if heartbeat stops)

routify:gateway:config-version  →  String (monotonic counter)
routify:gateway:instances       →  Redis Set of instanceId strings
```

**Instance ID:** Generated on startup as `hostname + ":" + port + ":" + UUID(8 chars)`. Stable for the lifetime of the process.

**Heartbeat scheduler:**
```java
@Scheduled(fixedDelayString = "${routify.gateway.cluster.heartbeat-interval-ms:10000}")
public void heartbeat() {
    var key = "routify:gateway:instances:" + instanceId;
    redisTemplate.opsForHash().putAll(key, Map.of(
        "hostname", hostname,
        "configVersion", String.valueOf(currentConfigVersion.get()),
        "routeCount", String.valueOf(activeRouteCount),
        "lastHeartbeatAt", Instant.now().toString()
    ));
    redisTemplate.expire(key, Duration.ofSeconds(30));
    redisTemplate.opsForSet().add("routify:gateway:instances", instanceId);
}
```

**Task list:**
- [ ] Create `GatewayInstanceRegistry` component
- [ ] Generate stable instance ID on startup
- [ ] Implement heartbeat scheduler
- [ ] Register/deregister on startup/shutdown (`@PreDestroy`)
- [ ] Add `RedisKeys.GATEWAY_INSTANCES_PREFIX` and `GATEWAY_CONFIG_VERSION` to `routify-common`
- [ ] Add config properties `routify.gateway.cluster.heartbeat-interval-ms`

---

### Step 2: Config Version Tracking

**Files to modify:**
- `routify-api-gateway/.../routing/DynamicRouteRefreshListener.java`

**On every route/filter event consumption:**
1. Apply the config change (existing behavior).
2. Increment local `configVersion` counter.
3. Atomically increment shared Redis counter: `INCR routify:gateway:config-version`.
4. Update the instance's heartbeat hash with the new version.

**Consistency rule:** An instance's `configVersion` should match the global Redis `config-version` within `heartbeat-interval * 3` (30s). If lagging longer, the instance is "stale".

**Task list:**
- [ ] Add local `AtomicLong configVersion` to route refresh listener
- [ ] Increment Redis config version on each event
- [ ] Update heartbeat with new version
- [ ] Add `routify.gateway.cluster.config-version` Micrometer gauge

---

### Step 3: Fleet Status RPC (admin-api)

**Files to modify:**
- `routify-admin-api/.../service/DashboardStatsService.java`
- `routify-admin-api/.../controller/AdminDashboardController.java`

**Endpoint:**
```
GET /api/v1/admin/gateway/fleet
```

**Logic:**
1. Read `routify:gateway:instances` Redis Set to get all instance IDs.
2. For each instance, read the heartbeat Hash.
3. Read global `routify:gateway:config-version`.
4. Compute per-instance status: `HEALTHY` (version matches, heartbeat recent), `STALE` (version behind), `UNRESPONSIVE` (heartbeat expired but still in set).
5. Return fleet summary.

**Response:**
```json
{
  "globalConfigVersion": 47,
  "instanceCount": 3,
  "healthyCount": 2,
  "staleCount": 1,
  "instances": [
    {
      "instanceId": "gateway-pod-abc123:8080:f7a2",
      "hostname": "gateway-pod-abc123",
      "configVersion": 47,
      "routeCount": 125,
      "status": "HEALTHY",
      "startedAt": "2026-07-15T10:30:00Z",
      "lastReloadAt": "2026-07-15T14:22:05Z",
      "lastHeartbeatAt": "2026-07-15T14:25:00Z",
      "uptimeHours": 3.9
    },
    {
      "instanceId": "gateway-pod-def456:8080:b3c1",
      "configVersion": 45,
      "status": "STALE",
      "...": "..."
    }
  ]
}
```

**Task list:**
- [ ] Implement fleet status query in `DashboardStatsService`
- [ ] Add REST endpoint
- [ ] Handle expired instances (remove from set if heartbeat hash is gone)

---

### Step 4: Dashboard Fleet Tab

**Files to create:**
- `routify-dashboard/src/modules/gateway/tabs/FleetTab.tsx`
- `routify-dashboard/src/modules/gateway/components/InstanceCard.tsx`

**UX:**
- Summary bar: "3 instances — 2 healthy, 1 stale".
- Instance cards: hostname, config version (green badge if matches global, amber if behind), route count, uptime, last reload time.
- Auto-refresh every 10s via `useRealtimeQuery`.
- Stale instances highlighted with amber border and "Config version behind" warning.

**Task list:**
- [ ] Create `FleetTab` component
- [ ] Create `InstanceCard` component
- [ ] Add to gateway page tab layout (alongside Q3 Health Dashboard tabs)
- [ ] Add fleet API function to `gatewayApi.ts`
- [ ] Add TypeScript types
- [ ] Add MSW mock handlers

---

### Step 5: Config Drift Webhook

**Publish `GATEWAY_CONFIG_DRIFT` webhook event when:**
- Any instance's config version is behind the global version for > 60s.
- An instance disappears from the registry (heartbeat expired).

**Implementation:** `DashboardStatsService.checkFleetHealth()` runs on a 60s schedule in admin-api. Compares each instance's version against global. If drift detected, publishes `AUDIT_EVENTS` with type `GATEWAY_CONFIG_DRIFT`.

**Task list:**
- [ ] Add `GATEWAY_CONFIG_DRIFT` to `WebhookEventType` enum
- [ ] Implement drift detection scheduler in admin-api
- [ ] Publish audit event on drift detection

---

## Acceptance Criteria

- [ ] Each gateway instance registers in Redis with heartbeat every 10s
- [ ] Fleet status endpoint returns all instances with config versions
- [ ] Stale instances (config version behind for >30s) are correctly identified
- [ ] Dashboard fleet tab shows instance cards with version badges
- [ ] Expired instances (heartbeat TTL) are automatically cleaned from the registry
- [ ] Config drift webhook fires when any instance lags for >60s

