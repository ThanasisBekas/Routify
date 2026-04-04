# routify-ai-service

LLM-powered request filtering and mutation intelligence layer for the Routify API Gateway.

## Overview

This service handles two AI-powered filter types for the gateway:

- **`AI_FILTER`** — evaluates a request against a natural-language policy and returns a verdict (ALLOW / BLOCK / FLAG).
- **`AI_MODIFIER`** — mutates a request (PII scrubbing, payload translation, header rewriting) before it reaches the upstream.

Both the API Gateway and the admin dashboard communicate with this service exclusively via **RabbitMQ RPC** — there are no REST controllers.

**Example policies:**
- `"Block requests that contain SQL injection patterns"`
- `"Deny requests from non-whitelisted User-Agent strings"`
- `"Scrub any email addresses from the request body before forwarding"`

## Architecture

```
[routify-api-gateway]
  AiGatewayFilterFactory / AiModifierGatewayFilterFactory
       │  RabbitMQ RPC (exchange: routify.ai-service)
       │  RK: ai.filter.evaluate | ai.modifier.evaluate
       ▼
[routify-ai-service]                          Port: 8086
  AiFilterRpcListener / AiModifierRpcListener
  AiFilterEvaluationService / AiModifierEvaluationService
       │
       ├── VerdictCacheService / MutationCacheService (Redis)  ← cache hit? return immediately
       │
       └── PromptBuilderService
               │  ChatClient (Spring AI → OpenAI gpt-4o-mini)
               ▼
          [OpenAI Chat Completions API]
               │  response_format: JSON_OBJECT (structured output)
               ▼
          AiVerdict / AiModification
               │
               ├── Redis: cache result (TTL configurable)
               └── Kafka: routify.ai.filter.decisions / routify.ai.modification.events → audit-service

[routify-admin-api]
  AiMessagingClient
       │  RabbitMQ RPC (same exchange: routify.ai-service)
       │  RK: ai.filter.evaluate | ai.modifier.evaluate
       ▼  (dry-run — no cache writes, no Kafka events)
[routify-ai-service]
```

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-ai-service` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8086` |
| Actuator port | `9086` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-ai-starter-model-openai` | Spring AI ChatClient (swap for `spring-ai-starter-model-ollama` for local LLM) |
| `spring-boot-starter-amqp` | RabbitMQ RPC listener (`@RabbitListener`) |
| `spring-boot-starter-data-redis` | Verdict / mutation cache |
| `spring-kafka` | Publish AI decision events to audit-service |
| `resilience4j-spring-boot3` | Circuit breaker + time limiter around LLM calls |
| `routify-common` | Shared DTOs, `RabbitTopology` constants |

## RabbitMQ Interface

This service has **no REST endpoints**. All callers use RabbitMQ Direct Reply-To RPC.

| Exchange | Queue | Routing Key | Called by |
|---|---|---|---|
| `routify.ai-service` | `routify.ai-service.filter.evaluate` | `ai.filter.evaluate` | Gateway (`AiGatewayFilterFactory`), admin-api (`AiMessagingClient`) |
| `routify.ai-service` | `routify.ai-service.modifier.evaluate` | `ai.modifier.evaluate` | Gateway (`AiModifierGatewayFilterFactory`), admin-api (`AiMessagingClient`) |

Reply timeouts (set by callers):
- Gateway filter: `AI_FILTER_REPLY_TIMEOUT_MS` = 3 500 ms
- Gateway modifier: `AI_MODIFIER_REPLY_TIMEOUT_MS` = 5 000 ms
- Admin-api dry-run: uses admin-api's default 5 s time limiter

## Configuration

Add to your `.env` file (project root):

```bash
# Required — service refuses to start without this
OPENAI_API_KEY=sk-...

# Optional — defaults shown
OPENAI_MODEL=gpt-4o-mini          # or gpt-4o for higher accuracy
OPENAI_TIMEOUT_SECONDS=10         # hard timeout per LLM call
AI_FALLBACK_ACTION=ALLOW          # verdict when OpenAI is unreachable
REDIS_HOST=localhost
KAFKA_BOOTSTRAP=localhost:9092
RABBITMQ_PASS=<required>
```

Key model settings (in `application.yml`):
- `temperature: 0.0` — **mandatory** for deterministic, cacheable verdicts
- `max-tokens: 256` — caps output; model only produces a small JSON object
- `response-format: JSON_OBJECT` — structured output enforcement

## Using Ollama (local LLM, air-gapped)

1. Install [Ollama](https://ollama.ai) and pull a model: `ollama pull qwen2.5:1.5b-instruct-q8_0`
2. In `pom.xml`, replace `spring-ai-starter-model-openai` with `spring-ai-starter-model-ollama`
3. In `application.yml`, replace the `spring.ai.openai` block with `spring.ai.ollama`
4. In `AiServiceConfig.java`, replace `OpenAiChatOptions` with `ChatOptions.builder()`
5. Set `OLLAMA_BASE_URL=http://localhost:11434`

No Java service logic, prompt, or DTO code changes required — `ChatClient` is provider-agnostic.

## Performance

| Scenario | p50 latency | p99 latency |
|---|---|---|
| Cache HIT | < 5 ms | < 10 ms |
| Cache MISS (gpt-4o-mini) | ~300 ms | ~2 s |
| Circuit breaker OPEN (fallback) | < 1 ms | < 1 ms |
| Hard timeout (10 s) → fallback | 10 000 ms | 10 000 ms |

**Latency strategies:**
1. **Redis verdict caching** — identical request fingerprints skip the LLM entirely
2. **Async evaluation mode** — set `evaluationMode: ASYNC` to decouple from request path
3. **Circuit breaker** — Resilience4j (`aiFilterLlm` / `aiModifierLlm`) opens after 50% failure rate in a 10-request window
4. **Hard timeout** — 10 s TimeLimiter (override via `OPENAI_TIMEOUT_SECONDS`) prevents tail latency cascades

## OpenAI Error Handling

| Error | Handling |
|---|---|
| Rate limit (429) | Spring AI retries automatically; circuit breaker opens after threshold |
| Context length exceeded | Fallback verdict applied; circuit does NOT open (non-retryable) |
| Invalid API key (401) | Fails fast at startup via `routify.required-secrets` validation |
| API timeout | Resilience4j TimeLimiter fires; `fallbackAction` verdict returned |
| OpenAI outage (503) | Circuit breaker opens; fallback applied for all subsequent requests |

## Metrics (Prometheus / Grafana)

Scraped at `http://localhost:9086/actuator/prometheus`

| Metric | Type | Tags |
|---|---|---|
| `routify.ai.filter.evaluations` | Counter | `action`, `mode`, `cached` |
| `routify.ai.filter.latency` | Timer | `action`, `cached` |
| `routify.ai.filter.fallbacks` | Counter | `routeId` |
| `routify.ai.filter.parse.errors` | Counter | — |

## Security

- **No inbound HTTP** — RabbitMQ-only ingress; not reachable from outside the cluster
- **API key protection** — `OPENAI_API_KEY` via environment variable; never logged
- **Prompt injection hardening** — user-controlled values are sanitized before embedding in prompts
- **Body cap** — `maxBodyBytes` (default 512) limits body surface area
- **Header redaction** — `Authorization`, `Cookie`, `X-Api-Key` always redacted from prompts

## Building & Running

```bash
# Build
mvn clean package -pl routify-ai-service -am -DskipTests

# Run
java -jar target/routify-ai-service-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.
