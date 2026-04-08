# routify-ai-service — Agent Guide

LLM-powered request filtering and mutation intelligence layer. Evaluates gateway requests against natural-language policies using Spring AI. **No REST endpoints** — all communication via RabbitMQ RPC.

## Runtime

- **Port:** 8086 (actuator: 9086)
- **Runtime:** Spring Web with Virtual Threads
- **No database** — uses Redis for verdict/mutation caching; publishes decisions to Kafka for audit

## Package Layout

```
io.routify.ai/
├── config/       # AiServiceConfig (ChatClient bean, OpenAI/Ollama options), RabbitMQ config
├── dto/          # AiFilterRequest/Response, AiModifierRequest/Response, AiVerdict, AiModification
├── exception/    # AI-specific exceptions
├── messaging/    # AiFilterRpcListener, AiModifierRpcListener (@RabbitListener handlers)
├── metrics/      # Prometheus counters/timers (routify.ai.filter.*)
├── prompt/       # PromptBuilderService (constructs LLM prompts from request context)
└── service/      # AiFilterEvaluationService, AiModifierEvaluationService,
                  # VerdictCacheService, MutationCacheService
```

## Key Patterns

### Evaluation flow
```
RabbitMQ request → RPC Listener → EvaluationService
                                   ├── Cache check (Redis) → hit? return immediately
                                   ├── PromptBuilderService → build prompt from request context
                                   ├── ChatClient (Spring AI) → OpenAI/Ollama API call
                                   ├── Parse JSON response → AiVerdict / AiModification
                                   ├── Cache result in Redis (TTL configurable)
                                   └── Publish decision event to Kafka (audit trail)
```

### Two filter types
| Type | Listener | Service | Kafka topic |
|---|---|---|---|
| `AI_FILTER` | `AiFilterRpcListener` | `AiFilterEvaluationService` | `routify.ai.filter.decisions` |
| `AI_MODIFIER` | `AiModifierRpcListener` | `AiModifierEvaluationService` | `routify.ai.modification.events` |

### Callers
- **Gateway** (`AiGatewayFilterFactory` / `AiModifierGatewayFilterFactory`): real-time evaluation with tight timeouts (3.5s / 5s). Results are cached and published to Kafka.
- **Admin-api** (`AiMessagingClient`): dry-run "Test Policy" / "Test Modification" — no cache writes, no Kafka events.

### LLM configuration
- `temperature: 0.0` — **mandatory** for deterministic, cacheable verdicts
- `response_format: JSON_OBJECT` — structured output enforcement
- `max-tokens: 256` (filter) / `1024` (modifier)
- Resilience4j circuit breaker (`aiFilterLlm` / `aiModifierLlm`) opens after 50% failure rate
- Hard timeout: `OPENAI_TIMEOUT_SECONDS` (default 10s) via TimeLimiter
- Fallback action: `AI_FALLBACK_ACTION` env var (default `ALLOW`)

### Swapping to Ollama (local LLM)
1. Replace `spring-ai-starter-model-openai` with `spring-ai-starter-model-ollama` in `pom.xml`
2. Update `application.yml`: replace `spring.ai.openai` block with `spring.ai.ollama`
3. In `AiServiceConfig.java`: replace `OpenAiChatOptions` with `ChatOptions.builder()`
4. Set `OLLAMA_BASE_URL=http://localhost:11434`
5. No prompt/DTO code changes needed — `ChatClient` is provider-agnostic

### Security
- No inbound HTTP — RabbitMQ-only ingress
- `Authorization`, `Cookie`, `X-Api-Key` headers are **always redacted** from prompts
- Request body is capped at `maxBodyBytes` (default 512) in prompts

## Build

```bash
mvn clean package -pl routify-ai-service -am -DskipTests
```

