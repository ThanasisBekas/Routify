# Initiative 08 — Integration Test Coverage Expansion

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Ongoing (Weeks 1–12) · **Owner:** All teams

---

## Problem Statement

The admin-api has only 2 integration test classes (`AdminAuthEndpointIT`, `AdminRoutesEndpointIT`) covering 2 of 12 controllers. The gateway has no per-filter-factory tests. Integration tests are globally disabled (`<skipITs>true</skipITs>`) due to a Docker Engine 29.x / Testcontainers compatibility issue. New features from Initiatives 01–07 will add 5+ more controllers — without IT coverage, regressions in the Kafka command → RabbitMQ reply contract will go undetected.

## Solution Overview

1. Resolve the Testcontainers compatibility issue.
2. Systematically add IT classes for every admin-api controller.
3. Add gateway filter factory unit tests.
4. Enable ITs in the CI pipeline.

---

## Detailed Implementation Steps

### Step 1: Resolve Testcontainers + Docker Engine 29.x

**Current state:**
- Parent POM overrides `docker-java` to `3.7.1` for Docker Engine 29.x API compatibility.
- `<skipITs>true</skipITs>` is set globally.
- Testcontainers version managed by Boot 3.5.13 (TC 1.21.4).

**Action plan:**
1. Verify TC 1.21.4 + docker-java 3.7.1 runs on Docker Engine 29.x.
2. If it fails: override `testcontainers.version` to `1.22.0+` in parent POM.
3. Run `AdminAuthEndpointIT` and `AdminRoutesEndpointIT` locally to confirm green.
4. If Docker Desktop on macOS is the issue: document the required Docker Desktop version.

**Task list:**
- [ ] Test existing ITs with current docker-java override
- [ ] Upgrade TC version if needed
- [ ] Document Docker Desktop version requirements
- [ ] Switch `<skipITs>false</skipITs>` in a new CI-specific Maven profile

---

### Step 2: Admin-API IT Classes

All tests extend `AdminApiIntegrationBase` which provides:
- Testcontainers for Kafka, RabbitMQ, Redis, PostgreSQL
- MockMvc with Spring Security context
- `generateTestJwt(userId, tenantId, role)` — generates unsigned JWT for test auth
- `mockRabbitReply(queue, response)` — stubs a RabbitMQ reply listener
- `drainTopic(topic, timeout)` — consumes Kafka messages for assertion

**Test class matrix:**

| # | Test Class | Controller | Key Scenarios |
|---|-----------|-----------|---------------|
| 1 | ✅ `AdminAuthEndpointIT` | `AdminAuthController` | Login, refresh, logout, change password |
| 2 | ✅ `AdminRoutesEndpointIT` | `AdminRoutesController` | CRUD, activate, deactivate, clone |
| 3 | `AdminFiltersEndpointIT` | `AdminFiltersController` | CRUD, attach to route, detach, enable/disable |
| 4 | `AdminUsersEndpointIT` | `AdminUsersController` | CRUD, password reset, role assignment |
| 5 | `AdminTenantsEndpointIT` | `AdminTenantsController` | Create, suspend, reactivate, list-active |
| 6 | `AdminCertificatesEndpointIT` | `AdminCertificatesController` | Upload PEM, revoke, gateway-snapshot |
| 7 | `AdminCertGroupsEndpointIT` | `AdminCertGroupsController` | Group CRUD, add/remove members |
| 8 | `AdminAuditEndpointIT` | `AdminAuditController` | Event query, request query, stats |
| 9 | `AdminReplayEndpointIT` | `AdminReplayController` | Single replay, bulk replay, stats |
| 10 | `AdminAiFilterEndpointIT` | `AdminAiFilterController` | Test-policy dry run, stats query |
| 11 | `AdminAiModifierEndpointIT` | `AdminAiModifierController` | Test-modification dry run |
| 12 | `AdminGatewayConfigEndpointIT` | `GatewayConfigController` | Get/save each config section (CORS, security headers, rate-limit, etc.) |
| 13 | `AdminDashboardEndpointIT` | `AdminDashboardController` | Stats, gateway-status, SSE subscription |

**Test pattern (example):**
```java
@Test
void createFilter_shouldPublishKafkaCommand_andReturn202() throws Exception {
    // Arrange
    var request = new CreateFilterRequest("rate-limit-100", null,
        FilterType.RATE_LIMIT_FIXED_WINDOW, Map.of("maxRequests", 100, "windowMs", 60000));

    // Act
    mockMvc.perform(post("/api/v1/admin/filters")
            .header("Authorization", "Bearer " + generateTestJwt(userId, tenantId, "TENANT_ADMIN"))
            .header("X-Tenant-Id", tenantId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    // Assert Kafka message published
    var records = drainTopic(KafkaTopics.FILTER_COMMANDS, Duration.ofSeconds(5));
    assertThat(records).hasSize(1);
    var command = objectMapper.readValue(records.get(0).value().toString(), CommandEvent.class);
    assertThat(command).isInstanceOf(CommandEvent.CreateFilter.class);
}

@Test
void getFilter_shouldReturnFilterFromRabbitReply() throws Exception {
    // Arrange — mock the RabbitMQ reply from route-service
    var filterDto = new FilterDefinitionDto(/* ... */);
    mockRabbitReply(RabbitTopology.QUEUE_FILTERS_GET,
        new QueryResponse.FilterDetail(filterDto));

    // Act
    mockMvc.perform(get("/api/v1/admin/filters/{id}", filterId)
            .header("Authorization", "Bearer " + generateTestJwt(userId, tenantId, "VIEWER"))
            .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("rate-limit-100"));
}
```

**Task list per test class:**
- [ ] Create test class extending `AdminApiIntegrationBase`
- [ ] Test happy path for each endpoint (GET list, GET detail, POST create, PUT update, DELETE)
- [ ] Test authorization (VIEWER cannot write, OPERATOR constraints)
- [ ] Test validation errors (missing required fields → 400)
- [ ] Test RabbitMQ timeout scenario (mock no reply → circuit breaker)
- [ ] Verify Kafka command payloads match expected structure

**Delivery schedule:**
- Week 1–2: `AdminFiltersEndpointIT`, `AdminUsersEndpointIT`, `AdminTenantsEndpointIT`
- Week 3–4: `AdminCertificatesEndpointIT`, `AdminCertGroupsEndpointIT`
- Week 5–6: `AdminAuditEndpointIT`, `AdminReplayEndpointIT`
- Week 7–8: `AdminAiFilterEndpointIT`, `AdminAiModifierEndpointIT`
- Week 9–10: `AdminGatewayConfigEndpointIT`, `AdminDashboardEndpointIT`
- Week 11–12: New initiative controllers (ApiKeys, Webhooks, Roles, Export/Import)

---

### Step 3: Gateway Filter Factory Unit Tests

**Location:** `routify-api-gateway/src/test/java/io/routify/gateway/filter/`

**Approach:** Use `MockServerWebExchange` to simulate request/response without a running gateway. Each test verifies the filter's behavior in isolation.

**Priority order (by usage frequency and complexity):**
1. `JwtAuthGatewayFilterFactoryTest` — valid JWT passes, expired JWT rejected, blocklisted JWT rejected
2. `FixedWindowRateLimitGatewayFilterFactoryTest` — under limit passes, over limit returns 429 (requires embedded Redis or mock)
3. `SlidingWindowRateLimitGatewayFilterFactoryTest` — sliding window boundary behavior
4. `ApiKeyAuthGatewayFilterFactoryTest` — valid key passes, missing key rejected, expired key rejected
5. `RequestHeaderModifyGatewayFilterFactoryTest` — headers added/set/removed correctly
6. `ResponseHeaderModifyGatewayFilterFactoryTest` — response headers modified
7. `CorrelationIdGatewayFilterFactoryTest` — generates UUID if absent, passes through if present
8. `RequestTimeoutGatewayFilterFactoryTest` — timeout produces 504
9. `JsonSchemaValidateGatewayFilterFactoryTest` — valid body passes, invalid body rejected
10. `JoltTransformGatewayFilterFactoryTest` — JSON transformation applied correctly

**Task list:**
- [ ] Create test class per filter factory (10 classes)
- [ ] Use `MockServerWebExchange` for request/response simulation
- [ ] Use embedded Redis (Testcontainers) for rate limit tests
- [ ] Use mock `ReactiveStringRedisTemplate` where full Redis is not needed

---

### Step 4: CI Pipeline Integration

**File to modify:** GitHub Actions workflow (`.github/workflows/ci.yml` or equivalent)

**Changes:**
```yaml
jobs:
  test:
    steps:
      - name: Unit tests
        run: mvn verify -DskipITs=true

      - name: Integration tests
        run: mvn verify -DskipITs=false -pl routify-admin-api -am
        env:
          DOCKER_HOST: unix:///var/run/docker.sock

      - name: Frontend tests
        run: |
          cd routify-dashboard
          npm ci
          npm run test:ci

      - name: Frontend E2E
        run: |
          cd routify-dashboard
          npm run test:e2e:ci
```

**Task list:**
- [ ] Create or update CI workflow
- [ ] Ensure Docker is available in CI runner (GitHub Actions has Docker by default)
- [ ] Set up test result reporting (JUnit XML → GitHub Actions)
- [ ] Add IT execution time budget alert (fail if ITs take > 5 min)

---

### Step 5: Frontend Test Expansion

**Current state:** `src/__tests__/` contains some tests. E2E covers login and routes flows.

**Expansion targets:**
- `src/__tests__/api/` — test API functions with MSW handlers (verify request shape)
- `src/__tests__/hooks/` — test `useWebSocket`, `useRealtimeQuery`, `useBootstrapAuth`
- `e2e/filters.spec.ts` — filter CRUD E2E flow
- `e2e/certificates.spec.ts` — cert upload and group management
- `e2e/audit.spec.ts` — audit log viewing and replay
- `e2e/gateway.spec.ts` — gateway config page navigation

**Task list:**
- [ ] Add 5+ API function unit tests (MSW)
- [ ] Add hook tests for `useRealtimeQuery`
- [ ] Add E2E specs for filters, certificates, audit, gateway pages
- [ ] Update MSW mock handlers to cover all new endpoints

---

## Coverage Targets

| Module | Current | Q3 Target |
|--------|---------|-----------|
| admin-api controllers (IT) | 2/12 (17%) | 12/12 (100%) |
| Gateway filter factories (unit) | 0/30 (0%) | 10/30 (33%) |
| Frontend API functions (unit) | Partial | All API modules |
| Frontend E2E flows | 2 specs | 6 specs |

---

## Acceptance Criteria

- [ ] All 12 existing admin-api controllers have IT coverage
- [ ] ITs run in CI on every merge to `develop` and `release/*`
- [ ] CI pipeline completes (unit + IT + frontend) in under 10 minutes
- [ ] Top-10 gateway filter factories have unit test coverage
- [ ] Zero flaky tests (all tests pass 10 consecutive runs)

