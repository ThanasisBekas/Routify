# routify-ai-service

LLM-powered request filtering intelligence layer for the Routify API Gateway.

## Overview

This microservice acts as the AI brain of the Routify filter chain. When the API Gateway
intercepts a route request with an `AI_FILTER` filter attached, it forwards the request
metadata to this service, which uses a Large Language Model (via **Spring AI + OpenAI**) to
evaluate the request against a natural-language policy defined by the operator.

**Example policies:**
- `"Block requests that contain SQL injection patterns"`
- `"Deny requests from non-whitelisted User-Agent strings"`
- `"Flag requests where the path contains encoded characters that could be path traversal attempts"`

## Architecture

```
[API Gateway]
  AiGatewayFilterFactory
       │  POST /api/v1/ai-filter/evaluate
       ▼
[routify-ai-service]                          Port: 8086
  AiFilterController
  AiFilterEvaluationService
       │
       ├── VerdictCacheService (Redis)  ← cache hit? return immediately
       │
       └── PromptBuilderService
               │  ChatClient (Spring AI → OpenAI gpt-4o-mini)
               ▼
          [OpenAI Chat Completions API]
               │  response_format: json_object (guaranteed valid JSON)
               ▼
          AiVerdict {action, reason, confidence}
               │
               ├── Redis: cache verdict (TTL configurable)
               └── Kafka: routify.ai.filter.decisions → audit-service
```

## Quick Start

### 1. Prerequisites

- Java 21+
- Running infrastructure: `docker compose -f docker-compose.yml up -d`
- An OpenAI API key from [platform.openai.com/api-keys](https://platform.openai.com/api-keys)

### 2. Configuration

Add the following to your `.env` file (project root):
```bash
# Required — the service will refuse to start without this (fail-fast validation)
OPENAI_API_KEY=sk-...

# Optional — defaults shown
OPENAI_MODEL=gpt-4o-mini          # or gpt-4o for higher accuracy
OPENAI_TIMEOUT_SECONDS=10         # hard timeout per LLM call
AI_FALLBACK_ACTION=ALLOW          # verdict when OpenAI is unreachable
REDIS_HOST=localhost
KAFKA_BOOTSTRAP=localhost:9092
```

### 3. Run locally

```bash
cd routify-ai-service
../mvnw spring-boot:run
```

Service starts on **port 8086** (app) and **9086** (management/actuator).

### 4. Run with Docker Compose

```bash
# From the project root:
mvn clean package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
```

## Using Ollama (local LLM, air-gapped)

To revert to a local Ollama instance:
1. Install [Ollama](https://ollama.ai) and pull a model: `ollama pull qwen2.5:1.5b-instruct-q8_0`
2. In `pom.xml`, replace `spring-ai-starter-model-openai` with `spring-ai-starter-model-ollama`
3. In `application.yml`, replace the `spring.ai.openai` block with the `spring.ai.ollama` block
4. In `AiServiceConfig.java`, replace `OpenAiChatOptions` with `ChatOptions.builder()`
5. In `docker-compose.app.yml`, restore the `ollama` service and `ollama_data` volume
6. Set `OLLAMA_BASE_URL=http://localhost:11434`

No Java service logic, prompt, or DTO code changes required — `ChatClient` is provider-agnostic.

## API Reference

### `POST /api/v1/ai-filter/evaluate`

Called by the API Gateway for every request on routes with `AI_FILTER` attached.

**Request:**
```json
{
  "routeId": "uuid",
  "routeName": "my-payments-api",
  "tenantId": "uuid",
  "filterConfig": {
    "policyDescription": "Block requests containing SQL injection patterns",
    "evaluationMode": "SYNC",
    "includeBody": false,
    "maxBodyBytes": 512,
    "fallbackAction": "ALLOW",
    "confidenceThreshold": 0.85,
    "cacheEnabled": true,
    "cacheTtlSeconds": 30
  },
  "requestContext": {
    "method": "POST",
    "path": "/api/v1/orders",
    "queryString": null,
    "clientIp": "203.0.113.42",
    "headers": { "Content-Type": "application/json" },
    "bodyExcerpt": null,
    "userContext": null
  }
}
```

**Response:**
```json
{
  "action": "ALLOW",
  "actionType": "ALLOW",
  "reason": "No SQL injection patterns detected in path or headers",
  "confidence": 0.97,
  "isAllowed": true,
  "cached": false,
  "latencyMs": 312,
  "evaluationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### `POST /api/v1/ai-filter/test-policy`

Dry-run endpoint for the Routify Dashboard's "Test Policy" button.
No caching, no Kafka events.

**Request:**
```json
{
  "policyDescription": "Block requests that appear to contain SQL injection",
  "sampleRequest": {
    "method": "GET",
    "path": "/api/v1/users?id=1 OR 1=1",
    "queryString": "id=1 OR 1=1",
    "clientIp": "10.0.0.1",
    "headers": {},
    "bodyExcerpt": null,
    "userContext": null
  }
}
```

## Performance

| Scenario | p50 latency | p99 latency |
|---|---|---|
| Cache HIT | < 5ms | < 10ms |
| Cache MISS (OpenAI gpt-4o-mini) | ~300ms | ~2s |
| Circuit breaker OPEN (fallback) | < 1ms | < 1ms |
| Hard timeout (10s) → fallback | 10,000ms | 10,000ms |

**Latency mitigation strategies:**
1. **Redis verdict caching** — identical request fingerprints skip the LLM entirely
2. **Async evaluation mode** — set `evaluationMode: ASYNC` to decouple from the request path
3. **Circuit breaker** — Resilience4j opens after 50% failure rate; fallback in microseconds
4. **Hard timeout** — 10s TimeLimiter (override via `OPENAI_TIMEOUT_SECONDS`) prevents tail latency from cascading

## OpenAI Error Handling

| Error | HTTP Code | Handling |
|---|---|---|
| Rate limit (429) | `TransientAiException` | Spring AI retries automatically; circuit breaker opens after threshold |
| Context length exceeded | `NonTransientAiException` | Fallback verdict applied; circuit does NOT open (non-retryable) |
| Invalid API key (401) | `NonTransientAiException` | App fails fast at startup via `required-secrets` validation |
| API timeout | `TimeoutException` | Resilience4j TimeLimiter fires; `llmFallback()` returns safe verdict |
| OpenAI outage (503) | `ResourceAccessException` | Circuit breaker opens; fallback verdict applied for all subsequent requests |

## Metrics (Prometheus / Grafana)

Scraped at `http://localhost:9086/actuator/prometheus`

| Metric | Type | Tags |
|---|---|---|
| `routify.ai.filter.evaluations` | Counter | `action`, `mode`, `cached` |
| `routify.ai.filter.latency` | Timer | `action`, `cached` |
| `routify.ai.filter.fallbacks` | Counter | `routeId` |
| `routify.ai.filter.parse.errors` | Counter | — |

## Security

- **Network isolation**: Kubernetes ClusterIP — not reachable from outside the cluster
- **API key protection**: `OPENAI_API_KEY` is injected via environment variable; never hardcoded or logged
- **Prompt injection hardening**: User-controlled values are sanitized and base64-encoded before embedding in prompts
- **Body cap**: `maxBodyBytes` (default 512) limits body surface area
- **Header redaction**: `Authorization`, `Cookie`, `X-Api-Key` are always redacted from prompts
- **JSON output enforcement**: Dual-layer — system prompt + OpenAI `response_format: json_object`
