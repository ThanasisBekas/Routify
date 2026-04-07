# Initiative GF-17 — GraphQL Depth Limit Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 5 (Extensions) · **Owner:** Gateway team  
> **Category:** Validation · **Priority:** Medium  
> **Filter type:** `GRAPHQL_DEPTH_LIMIT`  
> **Dependencies:** GF-02 (Unified Error Response Builder)  
> **Status:** ✅ **COMPLETED**

---

## Problem Statement

GraphQL APIs proxied through the gateway are vulnerable to depth-based denial-of-service attacks. A deeply nested query (e.g., `{ user { friends { friends { friends { ... } } } } }`) can cause exponential backend processing. There is no gateway-level protection against this.

## Solution Overview

A filter that parses incoming GraphQL query documents, computes query depth and complexity, and rejects queries that exceed configurable limits. Uses `graphql-java` for parsing (AST only, no execution engine).

---

## Detailed Implementation Steps

### Step 1: Add `graphql-java` Dependency

**Files to modify:**
- `routify-api-gateway/pom.xml`

**Dependency (parser only):**
```xml
<dependency>
    <groupId>com.graphql-java</groupId>
    <artifactId>graphql-java</artifactId>
    <version>22.3</version>
</dependency>
```

**Task list:**
- [x] Add `graphql-java` dependency to gateway POM
- [x] Verify no conflict with existing dependencies
- [x] Lazy-initialize parser on first GraphQL request (avoid startup latency)

---

### Step 2: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/validation/GraphQLDepthLimitGatewayFilterFactory.java`
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/validation/GraphQLQueryAnalyzer.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxDepth` | `int` | `10` | Maximum allowed query depth |
| `maxComplexity` | `int` | `100` | Maximum complexity score |
| `maxAliases` | `int` | `5` | Maximum aliases per query |
| `introspectionAllowed` | `boolean` | `false` | Whether `__schema`/`__type` allowed |
| `maxBatchSize` | `int` | `5` | Max operations in batched query |

**Implementation:**
1. Read request body (only for `POST` with `Content-Type: application/json`).
2. Parse the `query` field from the JSON body.
3. Parse the GraphQL query string using `graphql-java` `Parser`.
4. Walk the AST to compute depth, complexity, alias count.
5. Reject with 400 Bad Request via `GatewayProblemResponse` if any limit exceeded.
6. Re-emit the body for downstream (use `ServerHttpRequestDecorator`).

**Non-GraphQL detection:**
- Only process requests to paths matching a configurable pattern (e.g., `/graphql`).
- Check `Content-Type` — only parse `application/json`.
- If the body doesn't contain a `query` field, pass through unchanged.

**Task list:**
- [x] Create `GraphQLQueryAnalyzer` with AST traversal
- [x] Create filter factory
- [x] Parse request body for GraphQL query
- [x] Compute depth via recursive AST traversal
- [x] Compute complexity (1 per field, configurable multiplier for lists)
- [x] Count aliases
- [x] Detect and optionally block introspection queries
- [x] Re-emit body for downstream

---

### Step 3: Batch Query Support

**Implementation:**
If the request body is a JSON array (batched query), enforce `maxBatchSize` and evaluate each operation independently. The query is rejected if any single operation exceeds the depth/complexity limits.

**Task list:**
- [x] Detect JSON array (batched query)
- [x] Enforce `maxBatchSize`
- [x] Evaluate each operation independently

---

### Step 4: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `GRAPHQL_DEPTH_LIMIT`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `GRAPHQL_DEPTH_LIMIT` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

---

### Step 5: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/validation/GraphQLDepthLimitTest.java`
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/validation/GraphQLQueryAnalyzerTest.java`

**Test cases:**
- Query depth 5 with `maxDepth=10` → allowed
- Query depth 15 with `maxDepth=10` → rejected with 400
- Complexity 50 with `maxComplexity=100` → allowed
- Complexity 150 with `maxComplexity=100` → rejected
- 3 aliases with `maxAliases=5` → allowed
- 10 aliases with `maxAliases=5` → rejected
- Introspection query with `introspectionAllowed=false` → rejected
- Batched query with 3 ops and `maxBatchSize=5` → allowed
- Batched query with 10 ops and `maxBatchSize=5` → rejected
- Non-GraphQL request (REST) → passed through unchanged
- Fragment spreads followed correctly in depth calculation
- Invalid GraphQL query → 400 with parse error

**Task list:**
- [x] Write `GraphQLQueryAnalyzer` unit tests
- [x] Write filter factory integration tests
- [x] Write batch query tests
- [x] Write non-GraphQL passthrough tests

---

## Acceptance Criteria

- [x] GraphQL queries exceeding depth limit rejected with 400
- [x] Complexity scoring with configurable field costs
- [x] Alias limiting prevents resource exhaustion
- [x] Introspection queries optionally blocked
- [x] Batch query size limiting
- [x] Non-GraphQL requests pass through unchanged
- [x] Parser lazy-initialized (no startup latency impact)
