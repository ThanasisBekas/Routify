# Routify AI Filter — Technical Proposal
**Role:** Lead Software Architect & Senior Full-Stack Developer  
**Date:** April 3, 2026

---

## A. High-Level Architecture

### Data Flow

```
[Dashboard]
   │  POST /api/v1/admin/filters
   │  { filterType: "AI_FILTER", config: { policyDescription, evaluationMode, ... } }
   ▼
[routify-admin-api]  ──Kafka: FILTER_COMMANDS──▶  [routify-route-service]
                                                       │  persists FilterDefinition (JSONB config)
                                                       │  publishes GATEWAY_RELOAD to Kafka
                                                       ▼
                                               [routify-api-gateway]
                                               DynamicRouteRefreshListener
                                               RouteDefinitionBuilder → AiGatewayFilterFactory (PRE phase)

                                          [Live request arrives at gateway]
                                                       │
                              AiGatewayFilterFactory.apply(Config) reads AiFilterConfig from RouteSnapshot
                                                       │
                        ┌──────────── Redis cache hit? ────────────┐
                        │  YES: return cached verdict              │  NO: call AI service
                        ▼                                          ▼
                   ALLOW / BLOCK               POST http://routify-ai-service:8086/api/v1/ai/evaluate
                                                       │
                                           [routify-ai-service]  (new Spring Boot + Spring AI module)
                                           AiFilterEvaluationService
                                           PromptBuilderService ──▶ ChatClient ──▶ [LLM: OpenAI / Ollama]
                                                       │
                                           AiVerdict { action: ALLOW|BLOCK|FLAG, reason, confidence }
                                                       │
                                           Redis: cache verdict (routeId + request fingerprint, TTL 30s)
                                                       ▼
                                           Gateway: enforce verdict
                                           (ALLOW → chain.filter / BLOCK → 403 Forbidden)
                                                       │
                                           Kafka: publish AI_FILTER_DECISIONS telemetry event
                                                       ▼
                                           [routify-audit-service] persists AiFilterDecisionEvent
```

### How `routify-ai-service` Fits the Ecosystem

| Concern | How it integrates |
|---|---|
| Service discovery | Kubernetes ClusterIP, URL injected as `AI_SERVICE_URL` env var in gateway |
| Security | Internal-only (no Ingress), gateway calls use inter-service JWT (`GatewayPreAuthFilter` pattern) |
| Observability | Prometheus scrape + Grafana dashboard, same Kafka telemetry bus |
| Config | `docker-compose.app.yml` + `k8s/base/ai-service/deployment.yaml` following existing module pattern |

---

## B. New Microservice Design — `routify-ai-service`

### Package Structure (`gr.routify.ai`)

```
config/
  AiServiceConfig.java          – ChatClient bean, WebClient, async executor
  RedisConfig.java              – ReactiveRedisTemplate
  SecurityConfig.java           – inter-service JWT filter
  Resilience4jConfig.java       – CircuitBreaker around ChatClient calls
controller/
  AiFilterController.java       – POST /api/v1/ai/evaluate
                                  POST /api/v1/ai/test-policy
dto/
  AiEvaluationRequest.java      – routeId, routeName, tenantId, filterConfig, RequestContext
  AiVerdict.java                – action, reason, confidence, cached, latencyMs
  AiFilterConfig.java           – policyDescription, evaluationMode, includeBody, ...
  RequestContext.java           – method, path, headers, bodyExcerpt
service/
  AiFilterEvaluationService.java  – orchestrates cache → LLM → cache-write
  PromptBuilderService.java       – constructs System + User prompt pair
  VerdictCacheService.java        – Redis read/write with TTL
metrics/
  AiFilterMetrics.java          – Micrometer counters + timers
prompt/
  PromptTemplateLoader.java     – loads from classpath:prompts/
resources/
  prompts/
    ai-filter-system.txt        – static system prompt (strict JSON-only role)
    ai-filter-user.txt          – Mustache template for dynamic user prompt
  application.yml
```

### `pom.xml` Key Additions

```xml
<parent>
  <groupId>gr.routify</groupId>
  <artifactId>routify-parent</artifactId>
  <version>1.0.1-SNAPSHOT</version>
</parent>

<!-- In parent pom.xml <dependencyManagement>: -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-bom</artifactId>
  <version>1.0.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>

<!-- In routify-ai-service pom.xml: -->
<dependencies>
  <dependency>org.springframework.ai:spring-ai-openai-spring-boot-starter</dependency>
  <!-- swap for spring-ai-ollama-spring-boot-starter for local/air-gapped -->
  <dependency>gr.routify:routify-common</dependency>
  <dependency>org.springframework.boot:spring-boot-starter-web</dependency>
  <dependency>org.springframework.boot:spring-boot-starter-data-redis</dependency>
  <dependency>org.springframework.kafka:spring-kafka</dependency>
  <dependency>org.springframework.boot:spring-boot-starter-actuator</dependency>
  <dependency>io.micrometer:micrometer-registry-prometheus</dependency>
  <dependency>io.github.resilience4j:resilience4j-spring-boot3</dependency>
</dependencies>
```

### `application.yml`

```yaml
spring:
  application:
    name: routify-ai-service
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.0        # deterministic — critical for a security filter
          max-tokens: 256
  data:
    redis:
      host: ${REDIS_HOST:localhost}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}

server:
  port: 8086

routify:
  ai:
    cache-ttl-seconds: 30
    llm-timeout-ms: 3000
    fallback-action: ALLOW          # safe default when circuit is open
    circuit-breaker:
      failure-rate-threshold: 40
      wait-duration-open-state: 10s
```

### Prompt Engineering Strategy

**System prompt** (`ai-filter-system.txt`) — static, role-establishing:
```
You are a security filter evaluator. You receive structured JSON describing an HTTP request
and a policy rule. Respond ONLY with a JSON object in this exact schema:
{"action":"ALLOW"|"BLOCK"|"FLAG","reason":"<one sentence>","confidence":<0.0-1.0>}
Do NOT add any explanation outside the JSON object.
```

**User prompt** (dynamic per-request, Mustache template):
```
Policy: {{policyDescription}}

Request context:
- Route: {{routeId}} ({{routeName}})
- Tenant: {{tenantId}}
- Method: {{method}}
- Path: {{path}}
- Headers: {{headersJson}}
<data>
{{bodyExcerpt}}
</data>

Evaluate whether this request violates the policy.
```

> **Prompt injection hardening:** all user-controlled values (headers, body) are base64-encoded or wrapped in `<data>` XML tags. The system prompt explicitly instructs the LLM never to interpret `<data>` content as instructions.

### Latency Reduction Strategies

| Strategy | Mechanism |
|---|---|
| **Redis verdict caching** | Key: `routify:ai:{routeId}:{sha256(method+path+bodyHash)}`, TTL: configurable (default 30s). Identical requests skip LLM entirely. |
| **Async evaluation mode** | When `evaluationMode: ASYNC`, gateway always allows the request and AI verdict is evaluated off the critical path via Kafka for post-hoc auditing/flagging. |
| **Circuit breaker** | Resilience4j wraps every `ChatClient.call()`. On open circuit, `fallbackAction` (default: ALLOW) is returned in microseconds. |
| **Bounded thread pool** | Spring AI calls run on a dedicated `@Async` thread pool to avoid blocking the Netty event loop in the gateway. |
| **Hard timeout** | 3s max LLM wait; timeout triggers fallback action immediately. |

---

## C. API Contracts

### 1. Frontend ↔ Backend — AI Filter Config CRUD

Reuses the existing `POST /api/v1/admin/filters` endpoint in `AdminFiltersController`. No new endpoints needed — the `config` field is a freeform JSONB blob.

**Create AI Filter:**
```http
POST /api/v1/admin/filters
Authorization: Bearer <jwt>

{
  "routeId": "uuid",
  "filterType": "AI_FILTER",
  "name": "SQL Injection Guard",
  "config": {
    "policyDescription": "Block requests that appear to contain SQL injection patterns",
    "evaluationMode": "SYNC",
    "includeBody": false,
    "maxBodyBytes": 512,
    "fallbackAction": "ALLOW",
    "confidenceThreshold": 0.85,
    "cacheEnabled": true,
    "cacheTtlSeconds": 30
  }
}

Response 201:
{
  "id": "uuid",
  "filterType": "AI_FILTER",
  "name": "SQL Injection Guard",
  "status": "ACTIVE",
  "createdAt": "..."
}
```

**Retrieve / Update / Delete** — all follow the same existing `GET|PUT|DELETE /api/v1/admin/filters/{id}` pattern.

---

### 2. Gateway ↔ AI Microservice — Real-time Evaluation

```http
POST /api/v1/ai/evaluate
Content-Type: application/json
X-Correlation-Id: <propagated from incoming request>
X-Tenant-Id: <from route context>
Authorization: Bearer <inter-service JWT>

{
  "routeId": "uuid",
  "routeName": "my-payments-api",
  "tenantId": "uuid",
  "filterConfig": {
    "policyDescription": "Block requests that appear to contain SQL injection patterns",
    "evaluationMode": "SYNC",
    "includeBody": false,
    "maxBodyBytes": 512,
    "fallbackAction": "ALLOW",
    "confidenceThreshold": 0.85,
    "cacheEnabled": true,
    "cacheTtlSeconds": 30
  },
  "request": {
    "method": "POST",
    "path": "/api/v1/orders",
    "headers": {
      "Content-Type": "application/json",
      "User-Agent": "Mozilla/5.0"
    },
    "bodyExcerpt": "base64-encoded first 512 bytes | null"
  }
}

Response 200:
{
  "action": "ALLOW",             // "ALLOW" | "BLOCK" | "FLAG"
  "reason": "No SQL injection patterns detected in path or headers",
  "confidence": 0.97,
  "cached": false,
  "latencyMs": 312
}

Response 429 (AI service itself is rate-limited):
{ "error": "RATE_LIMITED", "retryAfterMs": 1000 }

Response 503 (circuit open):
{ "error": "AI_SERVICE_UNAVAILABLE", "fallbackAction": "ALLOW" }
```

**Test Policy (dry-run before activation):**
```http
POST /api/v1/ai/test-policy
{
  "policyDescription": "Block requests...",
  "sampleRequest": { "method": "GET", "path": "/api/v1/users?id=1 OR 1=1", "headers": {}, "bodyExcerpt": null }
}

Response 200:
{ "action": "BLOCK", "reason": "Path contains classic SQL OR injection", "confidence": 0.99 }
```

---

### 3. AI Service ↔ LLM (Spring AI Abstraction)

Spring AI's `ChatClient` abstracts provider entirely:

```java
@Bean
public ChatClient chatClient(ChatModel model) {
    return ChatClient.builder(model)
        .defaultSystem(promptTemplateLoader.loadSystemPrompt())
        .build();
}

// In AiFilterEvaluationService:
String response = chatClient.prompt()
    .user(promptBuilderService.buildUserPrompt(request, config))
    .options(ChatOptionsBuilder.builder().withTemperature(0.0f).build())
    .call()
    .content();
```

Swapping providers (OpenAI → Ollama → Anthropic) requires only changing the starter dependency and `spring.ai.*` config — zero code changes.

---

## D. Gateway Integration

### New Filter Factory

Create `AiGatewayFilterFactory` in `gr.routify.gateway.filter`, following the exact structure of the existing `FixedWindowRateLimitGatewayFilterFactory`:

```java
@Component
public class AiGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AiGatewayFilterFactory.Config> {

    private final WebClient aiServiceClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final AiGatewayMetrics metrics;

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // 1. Build cache key from routeId + request fingerprint
            // 2. Check Redis cache (if config.isCacheEnabled())
            // 3. Cache hit → return cached verdict
            // 4. Cache miss → POST to /api/v1/ai/evaluate with timeout
            //    .timeout(Duration.ofMillis(config.getTimeoutMs()))
            //    .onErrorResume(ex -> Mono.just(fallbackVerdict(config)))
            // 5. If verdict.action == BLOCK → write 403 response (same as rate limit pattern)
            // 6. Else → chain.filter(exchange)
            // 7. Async: publish verdict to Kafka AI_FILTER_DECISIONS topic
        };
    }

    @Data
    public static class Config {
        private String policyDescription;
        private String evaluationMode = "SYNC";   // "SYNC" | "ASYNC"
        private boolean includeBody = false;
        private int maxBodyBytes = 512;
        private String fallbackAction = "ALLOW";
        private double confidenceThreshold = 0.85;
        private boolean cacheEnabled = true;
        private int cacheTtlSeconds = 30;
        private int timeoutMs = 3000;
    }
}
```

### `RouteDefinitionBuilder` Update

Add the following case to the filter switch statement in `RouteDefinitionBuilder.buildFilterDefinition()`:

```java
case "AI_FILTER" -> customFilter("AiFilter", cfg);
```

### `FilterType` Enum Update (`routify-common`)

```java
// In FilterType.java:
AI_FILTER
```

### Gateway Configuration

Add to `application.yml`:
```yaml
routify:
  ai-service:
    url: ${AI_SERVICE_URL:http://routify-ai-service:8086}
    timeout-ms: 3000
```

---

## E. Frontend Integration

### 1. Type Updates (`src/types/index.ts`)

```typescript
// Add to FilterType union:
| 'AI_FILTER'
```

### 2. Filter Catalogue (`FilterDefinitionForm.tsx`)

Add a new `'AI'` category entry in the `FILTER_TYPES` array:
```typescript
{
  value: 'AI_FILTER',
  label: 'AI Filter',
  category: 'AI',
  description: 'Intelligent, policy-driven request filtering powered by LLM',
  color: 'text-fuchsia-400',
  icon: SparklesIcon
}
```

### 3. Config Fields (`FilterConfigFields.tsx`)

New `AI_FILTER` case renders:
- `<textarea>` — `policyDescription` (multiline, required)
- `<select>` — `evaluationMode`: SYNC / ASYNC
- `<select>` — `fallbackAction`: ALLOW / BLOCK
- `<toggle>` — `includeBody`, `cacheEnabled`
- `<number input>` — `confidenceThreshold` (0.0–1.0), `cacheTtlSeconds`, `maxBodyBytes`
- **"Test Policy" button** → calls `POST /api/v1/ai/test-policy` and displays verdict inline

### 4. Default Config (`filterConfigConstants.ts`)

```typescript
AI_FILTER: {
  policyDescription: '',
  evaluationMode: 'SYNC',
  includeBody: false,
  maxBodyBytes: 512,
  fallbackAction: 'ALLOW',
  confidenceThreshold: 0.85,
  cacheEnabled: true,
  cacheTtlSeconds: 30
}
```

### 5. AI Monitoring Module (`src/modules/ai/`)

The directory already exists. Populate with:

**`AiFilterStatsPage.tsx`**
- Per-route breakdown of ALLOW / BLOCK / FLAG verdicts (pie + time-series charts using `recharts`)
- Average LLM latency, cache hit rate
- Recent AI decision log (action, reason, route, timestamp) — reads from audit-service

**`useAiFilterStats.ts`** — TanStack Query hook:
```typescript
export const useAiFilterStats = (routeId: string) =>
  useQuery({
    queryKey: ['ai-filter-stats', routeId],
    queryFn: () => auditApi.getAiFilterStats(routeId),
    refetchInterval: 30_000
  });
```

**`auditApi` addition:**
```typescript
getAiFilterStats: (routeId: string) =>
  apiClient.get(`/audit/ai-filter-stats?routeId=${routeId}`)
```

No new Zustand stores — TanStack Query handles all server state, consistent with every other module.

---

## F. Implementation Roadmap

### Phase 1 — AI Microservice Scaffolding *(~2 weeks)*
**Goal:** Standalone, testable AI service

- [ ] Create `routify-ai-service` Maven module; add to root `pom.xml` `<modules>`
- [ ] Add Spring AI BOM to parent `pom.xml` `<dependencyManagement>`
- [ ] Implement `AiFilterController`, `AiFilterEvaluationService`, `PromptBuilderService`
- [ ] Implement `VerdictCacheService` (Redis), `Resilience4jConfig` (circuit breaker)
- [ ] Write prompt templates; wire `ChatClient` bean for OpenAI + Ollama profiles
- [ ] Unit tests: `PromptBuilderServiceTest`, `VerdictCacheServiceTest`
- [ ] Add service to `docker-compose.app.yml`
- [ ] Create `k8s/base/ai-service/deployment.yaml` + `service.yaml` + `kustomization.yaml`
- [ ] Add `OPENAI_API_KEY` to K8s Secret `routify-secrets`

**Deliverable:** `POST /api/v1/ai/evaluate` returns LLM verdicts, tested in isolation.

---

### Phase 2 — Gateway Filter Integration *(~1 week)*
**Goal:** Gateway enforces AI filter decisions on live traffic

- [ ] Add `AI_FILTER` to `FilterType` enum in `routify-common`
- [ ] Implement `AiGatewayFilterFactory` in `routify-api-gateway`
- [ ] Add `case "AI_FILTER"` to `RouteDefinitionBuilder.buildFilterDefinition()`
- [ ] Add `AI_SERVICE_URL` env var to gateway deployment ConfigMap
- [ ] Add `AiFilterMetrics` (Micrometer); add scrape config to `prometheus.yml`
- [ ] Integration test: SYNC block returns 403; circuit-open returns fallback action

**Deliverable:** Routes with `AI_FILTER` configs are intercepted and enforced end-to-end.

---

### Phase 3 — Backend Config Management *(~3 days)*
**Goal:** Admin API fully supports AI filter lifecycle

- [ ] Add `AI_FILTER` validation in `FilterDefinitionService` (reject empty `policyDescription`)
- [ ] Add `KafkaTopics.AI_FILTER_DECISIONS` constant in `routify-common`
- [ ] Implement `AiFilterDecisionEvent` record (new Kafka message type in `routify-common`)
- [ ] `routify-audit-service`: consume `AI_FILTER_DECISIONS` topic, persist to `audit_events`
- [ ] `routify-audit-service`: expose `GET /audit/ai-filter-stats?routeId=` endpoint

**Deliverable:** AI filter decisions are persisted and queryable via audit service.

---

### Phase 4 — Frontend UI *(~1 week)*
**Goal:** Users can configure, test, and monitor AI filters from the dashboard

- [ ] Add `AI_FILTER` to TypeScript types union
- [ ] Add `AI_FILTER` to filter catalogue in `FilterDefinitionForm.tsx`
- [ ] Implement `AI_FILTER` config fields in `FilterConfigFields.tsx` (including "Test Policy")
- [ ] Add default config in `filterConfigConstants.ts`
- [ ] Implement `AiFilterStatsPage.tsx` and `useAiFilterStats.ts` in `modules/ai/`
- [ ] Add AI Stats route to `App.tsx` and sidebar navigation

**Deliverable:** Full UI for create / edit / delete / test / monitor AI filters.

---

### Phase 5 — Observability & Hardening *(~1 week)*
**Goal:** Production-ready, monitored, secured

- [ ] Grafana dashboard: AI filter ALLOW/BLOCK rates, p99 LLM latency, cache hit %, circuit state
- [ ] Rate-limit the `/api/v1/ai/evaluate` endpoint itself (per-tenant) using existing `RATE_LIMIT_FIXED_WINDOW` filter
- [ ] Load test: verify circuit breaker trips correctly under LLM degradation
- [ ] Security review: prompt injection test suite against `PromptBuilderService`
- [ ] Documentation: README for `routify-ai-service`, operator guide for writing effective policies
- [ ] Add `routify-ai-service` Dockerfile (follows existing module Dockerfile pattern)

**Deliverable:** Feature is production-hardened with full observability and security controls.

---

## G. Potential Challenges & Mitigations

### 1. Latency Overhead
**Risk:** LLM calls add 200–2000ms to every intercepted request, impacting SLA.

**Mitigations:**
- **Redis verdict caching** — same Redis instance already used for rate limiting. Key: `routify:ai:{routeId}:{sha256(method+path+bodyHash)}`. Cache-hit p99 target: < 5ms.
- **Async evaluation mode** — for non-critical or monitoring-only use cases, decouple verdict from request path entirely. Gateway allows request immediately; LLM verdict arrives via Kafka for audit/flagging.
- **Circuit breaker** — Resilience4j fail-fasts on LLM degradation; `fallbackAction` returned in microseconds.
- **Hard timeout** — 3s cap with immediate fallback prevents tail latency from cascading.
- **Model selection** — `gpt-4o-mini` averages ~400ms; Ollama locally can average ~200ms on GPU.

---

### 2. AI Hallucination / Non-Determinism
**Risk:** LLM returns inconsistent verdicts for identical inputs, causing random request blocking.

**Mitigations:**
- `temperature: 0.0` — makes the LLM maximally deterministic.
- **Strict output schema** — system prompt demands JSON-only response; response is validated against the schema; malformed responses trigger `fallbackAction`.
- **Confidence threshold** — verdicts below `confidenceThreshold` (default 0.85) are treated as inconclusive and `fallbackAction` is applied.
- **Request fingerprint caching** — same fingerprint always returns the same cached verdict, eliminating repeated LLM calls for identical requests.
- **ASYNC rollout** — run in ASYNC/monitoring mode first to empirically validate LLM accuracy before switching to blocking SYNC enforcement.

---

### 3. Prompt Injection
**Risk:** Malicious request bodies override the system prompt, causing the LLM to always return ALLOW.

**Mitigations:**
- All user-controlled values (headers, body) are **base64-encoded** before embedding in the prompt.
- Request body is wrapped in `<data>` XML tags; system prompt explicitly instructs the LLM to never treat `<data>` content as instructions.
- Hard `maxBodyBytes` cap (default 512) limits the attack surface.
- Dedicated prompt injection test suite as part of Phase 5 hardening.

---

### 4. Configuration Complexity
**Risk:** Operators write vague or contradictory natural-language policies that produce unreliable filter behaviour.

**Mitigations:**
- **"Test Policy" dry-run** — `POST /api/v1/ai/test-policy` lets operators validate a policy against sample requests before activating it. Results show verdict, reason, and confidence inline in the UI.
- Policy writing guidelines in the operator documentation.
- Pre-built policy templates (SQL injection, XSS, PII detection) available in the filter catalogue.

---

### 5. Scalability
**Risk:** Every gateway pod calls the AI service, which calls an external LLM API — potential rate limiting and cost explosion.

**Mitigations:**
- **Shared Redis cache** across all gateway pods — a single LLM call serves all pods for identical requests.
- **Kubernetes HPA** on `routify-ai-service` (scale on CPU/RPS).
- **Per-tenant rate limiting** on `/api/v1/ai/evaluate` using the existing `RATE_LIMIT_FIXED_WINDOW` filter.
- **Cost controls** — `max-tokens: 256` + `gpt-4o-mini` keeps per-call cost at ~$0.0001. Route-level filter enable/disable gives operators fine-grained control.

---

### 6. Security of the AI Service
**Risk:** The `/api/v1/ai/evaluate` endpoint exposed to internal network could be abused.

**Mitigations:**
- Kubernetes **ClusterIP service** — no Ingress, not reachable from outside the cluster.
- **Inter-service JWT authentication** — follows the `GatewayPreAuthFilter` pattern already established in `routify-route-service` and `routify-identity-service`.
- Only the API Gateway service account can call the AI service.

---

## Open Questions for Stakeholder Sign-off

1. **LLM Provider**: OpenAI `gpt-4o-mini` (managed, low-latency) vs. Ollama `llama3` (self-hosted, air-gapped)? Should the service support runtime switching?
2. **Evaluation Mode Default**: Should `ASYNC` (non-blocking, monitoring-only) be the forced default for initial rollout, with `SYNC` (blocking enforcement) gated behind an `ENTERPRISE` plan flag?
3. **Verdict Audit Retention**: Should AI decisions reuse the existing `RequestTelemetryEvent` schema or warrant a dedicated `AiFilterDecisionEvent` type for richer metadata?
4. **Policy Templates**: Should Phase 1 include a library of pre-built policy templates (OWASP Top 10 patterns) or defer to Phase 5?
