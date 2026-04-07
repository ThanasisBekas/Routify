# Initiative 13 — GraphQL Analytics API

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 5–8 · **Owner:** Admin-API team  
> **Prerequisites:** Q3-05 (Health Dashboard backend stats), existing audit-service query handlers

---

## Problem Statement

Analytics consumers (Grafana dashboards, custom UIs, reporting scripts) must make multiple REST calls and aggregate client-side for questions like "error rate by route and hour for the last 7 days". The fixed-schema REST stats endpoints (`/api/v1/admin/stats`, route-level stats, AI stats) don't support custom aggregations, time bucketing, or field selection.

## Solution Overview

Add a GraphQL API mounted alongside the existing REST API at `/api/v1/admin/graphql`. The schema exposes composable analytics types with flexible filtering, time-series bucketing, and field selection. Existing backend data sources (audit-service RabbitMQ queries, route-service queries, DashboardStatsService) are reused.

---

## Detailed Implementation Steps

### Step 1: Add GraphQL Dependency

**File to modify:**
- `routify-admin-api/pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-graphql</artifactId>
</dependency>
```

**Application properties:**
```yaml
spring:
  graphql:
    path: /api/v1/admin/graphql
    graphiql:
      enabled: true  # dev profile only
      path: /api/v1/admin/graphiql
    schema:
      locations: classpath:graphql/
```

**Task list:**
- [x] Add dependency
- [x] Configure properties
- [x] Enable GraphiQL in dev profile only

---

### Step 2: Schema Definition

**File to create:**
- `routify-admin-api/src/main/resources/graphql/schema.graphqls`

```graphql
type Query {
  routeAnalytics(
    tenantId: ID!
    routeId: ID
    from: DateTime!
    to: DateTime!
    granularity: Granularity = HOUR
  ): RouteAnalyticsResult!

  tenantUsage(
    tenantId: ID!
    from: DateTime!
    to: DateTime!
  ): TenantUsageResult!

  aiFilterAnalytics(
    tenantId: ID!
    filterId: ID
    from: DateTime!
    to: DateTime!
    granularity: Granularity = DAY
  ): AiFilterAnalyticsResult!

  certExpiryReport(tenantId: ID!): CertExpiryReport!

  auditTimeline(
    tenantId: ID!
    aggregateType: String
    from: DateTime!
    to: DateTime!
    limit: Int = 100
  ): [AuditTimelineEntry!]!
}

enum Granularity { MINUTE, HOUR, DAY, WEEK }

type RouteAnalyticsResult {
  routes: [RouteMetrics!]!
  totals: AggregateMetrics!
}

type RouteMetrics {
  routeId: ID!
  routeName: String!
  timeSeries: [TimeBucket!]!
  aggregate: AggregateMetrics!
}

type TimeBucket {
  timestamp: DateTime!
  requestCount: Long!
  errorCount: Long!
  errorRate: Float!
  p50LatencyMs: Float!
  p95LatencyMs: Float!
  p99LatencyMs: Float!
  avgLatencyMs: Float!
  statusCodes: [StatusCodeCount!]!
}

type StatusCodeCount {
  code: Int!
  count: Long!
}

type AggregateMetrics {
  totalRequests: Long!
  totalErrors: Long!
  errorRate: Float!
  avgLatencyMs: Float!
  p50LatencyMs: Float!
  p95LatencyMs: Float!
  p99LatencyMs: Float!
}

type TenantUsageResult {
  tenantId: ID!
  plan: String!
  routes: QuotaUsage!
  filters: QuotaUsage!
  requests: QuotaUsage!
  dailyUsage: [DailyUsage!]!
}

type QuotaUsage {
  used: Int!
  limit: Int!
  percentage: Float!
}

type DailyUsage {
  date: Date!
  requestCount: Long!
  errorCount: Long!
  routeCount: Int!
}

type AiFilterAnalyticsResult {
  filters: [AiFilterMetrics!]!
}

type AiFilterMetrics {
  filterId: ID!
  filterName: String!
  totalDecisions: Long!
  allowCount: Long!
  blockCount: Long!
  flagCount: Long!
  avgLatencyMs: Float!
  cacheHitRate: Float!
  accuracyScore: Float
  timeSeries: [AiFilterTimeBucket!]!
}

type AiFilterTimeBucket {
  timestamp: DateTime!
  decisions: Long!
  blockRate: Float!
  avgLatencyMs: Float!
}

type CertExpiryReport {
  totalCerts: Int!
  expiringSoon: Int!
  expired: Int!
  certs: [CertExpiry!]!
}

type CertExpiry {
  certId: ID!
  alias: String!
  daysUntilExpiry: Int!
  notAfter: DateTime
  status: String!
  autoRenew: Boolean!
}

type AuditTimelineEntry {
  eventId: ID!
  eventType: String!
  aggregateType: String!
  aggregateId: ID!
  actorId: String
  occurredAt: DateTime!
}

scalar DateTime
scalar Date
scalar Long
```

**Task list:**
- [x] Define complete schema
- [x] Add custom scalar type configuration (DateTime, Date, Long)

---

### Step 3: DataFetcher Implementation

**Files to create:**
- `routify-admin-api/.../graphql/AnalyticsGraphQLController.java`
- `routify-admin-api/.../graphql/GraphQLSecurityInterceptor.java`

**Controller:**
```java
@Controller
public class AnalyticsGraphQLController {

    @QueryMapping
    public RouteAnalyticsResult routeAnalytics(
            @Argument UUID tenantId, @Argument UUID routeId,
            @Argument Instant from, @Argument Instant to,
            @Argument Granularity granularity) {
        // Delegates to AuditMessagingClient for time-bucketed request stats
    }

    @QueryMapping
    public TenantUsageResult tenantUsage(@Argument UUID tenantId, ...) {
        // Delegates to IdentityMessagingClient + AuditMessagingClient
    }

    @QueryMapping
    public AiFilterAnalyticsResult aiFilterAnalytics(...) {
        // Delegates to AuditMessagingClient for AI decision stats
    }
}
```

**Security interceptor:** Validates JWT from `Authorization` header, enforces tenant isolation (user can only query their own tenant), checks granular permissions if Q3 RBAC is active.

**Task list:**
- [x] Create controller with all `@QueryMapping` methods
- [x] Implement security interceptor (JWT + tenant scoping)
- [x] Wire to existing messaging clients for data
- [x] Add DataLoader batching for N+1 prevention on `routeAnalytics.routes` list

---

### Step 4: Audit-Service Time-Bucketed Queries

**Files to modify:**
- `routify-audit-service/.../messaging/` — add new RabbitMQ handler

**New RabbitMQ query:**
```java
QUEUE_AUDIT_TIME_SERIES = "routify.audit-service.time-series";
RK_AUDIT_TIME_SERIES    = "audit.time-series";
```

**Query request:**
```java
record TimeSeriesQuery(
    UUID tenantId, UUID routeId, // routeId nullable = all routes
    Instant from, Instant to,
    String granularity,  // MINUTE, HOUR, DAY, WEEK
    List<String> metrics // requestCount, errorCount, latencyP50, latencyP95, latencyP99
) implements QueryRequest {}
```

**Implementation:** SQL `date_trunc(granularity, requested_at)` grouping on `request_log` table.

**Task list:**
- [x] Add RabbitMQ topology constants
- [x] Implement time-series aggregation SQL query
- [x] Create handler in audit-service
- [x] Add request/response types to `routify-common`

---

### Step 5: Dashboard Integration (Optional)

Selected dashboard components can optionally use GraphQL for complex views:
- Route health heatmap: single GraphQL query replaces multiple REST calls for per-route time-bucketed metrics.
- Cert expiry report: single query returns all cert expiry data.

**Task list:**
- [x] Add `graphql-request` lightweight client library to dashboard (or use `fetch`)
- [x] Convert route health tab data source to GraphQL (optional, feature-flagged)
- [x] Add GraphQL query builder utility in `src/api/graphqlClient.ts`

---

### Step 6: Query Depth & Rate Limiting

**Security hardening:**
- Max query depth: 5 levels (prevents deeply nested abuse).
- Max query complexity: computed from field weights (prevent expensive aggregations).
- Rate limit: 100 GraphQL queries per minute per tenant (reuse Redis rate limiter).

**Task list:**
- [x] Configure `spring.graphql.schema.inspection` depth/complexity limits
- [x] Add rate limiting middleware for GraphQL endpoint
- [x] Document query limits in API documentation

---

## Acceptance Criteria

- [x] `routeAnalytics` query returns per-route time-bucketed metrics with configurable granularity
- [x] `tenantUsage` query returns quota usage with daily trend
- [x] `aiFilterAnalytics` query returns decision stats per filter with time series
- [x] GraphiQL playground accessible in dev profile for query exploration
- [x] JWT authentication enforced on all GraphQL queries
- [x] Tenant isolation prevents cross-tenant data access
- [x] Query depth limited to 5 levels

