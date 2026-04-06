# Initiative GF-13 — Circuit Breaker v2 Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 2 (High-Priority Filters) · **Owner:** Gateway team  
> **Category:** Resilience · **Priority:** High  
> **Filter type:** `CIRCUIT_BREAKER_V2`  
> **Dependencies:** GF-02 (Unified Error Response Builder), Q3-05 (Gateway Health Dashboard v2 for CB visualization)

---

## Problem Statement

The deprecated `CIRCUIT_BREAKER` filter type delegated to SCG's built-in `CircuitBreaker` filter with minimal configuration. It has been deprecated because it offered no per-route customization, no integration with the dashboard's circuit breaker visualization, and no slow-call detection. Operators need granular circuit breaker control per route.

## Solution Overview

A custom circuit breaker filter powered by Resilience4j with per-route instances, configurable thresholds (failure rate, slow call rate), half-open probing, and real-time state broadcast via WebSocket for dashboard visualization.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/resilience/CircuitBreakerV2GatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `failureRateThreshold` | `float` | `50.0` | Failure rate % to trip circuit |
| `slowCallRateThreshold` | `float` | `80.0` | Slow call rate % to trip circuit |
| `slowCallDurationMs` | `long` | `3000` | Threshold for slow calls |
| `slidingWindowSize` | `int` | `10` | Sliding window size |
| `slidingWindowType` | `String` | `COUNT_BASED` | `COUNT_BASED` or `TIME_BASED` |
| `minimumNumberOfCalls` | `int` | `5` | Min calls before evaluation |
| `waitDurationInOpenStateMs` | `long` | `60000` | Wait before half-open |
| `permittedNumberOfCallsInHalfOpenState` | `int` | `3` | Calls allowed in half-open |
| `fallbackStatus` | `int` | `503` | HTTP status when open |
| `fallbackBody` | `String` | ProblemDetail JSON | Response body when open |

**Implementation:**
1. Create a `CircuitBreaker` instance per route ID using Resilience4j's `CircuitBreakerRegistry`.
2. Wrap `chain.filter(exchange)` with `ReactorResilience4j.decorateMono()`.
3. On circuit OPEN → return `GatewayProblemResponse` with `fallbackStatus` and `fallbackBody`.
4. Register event listeners for state transitions.

**Task list:**
- [ ] Create filter factory with Resilience4j `CircuitBreaker` per route
- [ ] Implement reactive wrapping with `ReactorResilience4j.decorateMono()`
- [ ] Handle OPEN state with `GatewayProblemResponse` fallback
- [ ] Support all 10 config parameters

---

### Step 2: State Broadcast via WebSocket

**Implementation:**
On state transitions (`CLOSED → OPEN`, `OPEN → HALF_OPEN`, `HALF_OPEN → CLOSED`):
1. Publish a WebSocket event to `/topic/events` via the STOMP broker.
2. Event payload: `{ type: "CIRCUIT_BREAKER_STATE_CHANGE", routeId, fromState, toState, timestamp, failureRate, slowCallRate }`.
3. The dashboard's `wsStore` updates the circuit breaker visualization in real time.

**Task list:**
- [ ] Register `CircuitBreaker.EventPublisher` event listener
- [ ] Publish state transition events via WebSocket STOMP
- [ ] Include failure rate and slow call rate in event payload

---

### Step 3: Manual Override Endpoint

**Admin-api endpoints:**
```
POST /api/v1/admin/routes/{id}/circuit-breaker/force-open
POST /api/v1/admin/routes/{id}/circuit-breaker/force-closed
POST /api/v1/admin/routes/{id}/circuit-breaker/reset
```

Each dispatches a Kafka command that the gateway consumes to call `circuitBreaker.transitionToForcedOpenState()` / `transitionToClosedState()` / `reset()`.

**Task list:**
- [ ] Add `CommandEvent.ForceCircuitBreaker` to `routify-common`
- [ ] Add 3 admin-api endpoints
- [ ] Gateway consumer applies state transitions
- [ ] Dashboard buttons for force-open/close/reset

---

### Step 4: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `CIRCUIT_BREAKER_V2`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [ ] Add `CIRCUIT_BREAKER_V2` to `FilterType` enum
- [ ] Add to TypeScript `FilterType` union
- [ ] Add filter config form in dashboard

---

### Step 5: Metrics

Resilience4j auto-registers Micrometer metrics under `resilience4j.circuitbreaker.*`. Add `routeId` tag for per-route visibility.

**Additional custom metrics:**
- `routify.filter.circuit_breaker.state` — gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN, 3=FORCED_OPEN)

**Task list:**
- [ ] Ensure Resilience4j metrics are tagged with `routeId`
- [ ] Register custom state gauge

---

### Step 6: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/resilience/CircuitBreakerV2Test.java`

**Test cases:**
- 5 failures → circuit opens → subsequent requests get 503
- Circuit opens → wait duration expires → half-open → success → closes
- Slow calls exceed threshold → circuit opens
- Manual force-open → all requests get 503
- Manual reset → circuit closes
- State transition publishes WebSocket event
- Per-route isolation (route A's circuit doesn't affect route B)

**Task list:**
- [ ] Write state transition tests
- [ ] Write slow call detection tests
- [ ] Write manual override tests
- [ ] Write per-route isolation tests

---

## Acceptance Criteria

- [ ] Per-route Resilience4j circuit breaker instances
- [ ] Failure rate and slow call rate thresholds trigger circuit open
- [ ] Half-open probing with configurable permitted calls
- [ ] State transitions broadcast via WebSocket for dashboard visualization
- [ ] Manual force-open/close/reset via admin-api endpoints
- [ ] Resilience4j Micrometer metrics tagged by `routeId`

