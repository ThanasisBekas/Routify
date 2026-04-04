# Routify — AI Agent Guide

## Architecture Overview

Routify is a **zero-downtime API Gateway Platform** built as a Maven multi-module monorepo with one React frontend.

```
routify-dashboard  (React/Vite, port 5173)
       │
routify-admin-api  (BFF, port 8082) ← sole backend for the dashboard
       │ Kafka commands (writes) + RabbitMQ request/reply (reads)
       ├── routify-identity-service  (port 8083) — JWT issuance, users, tenants
       ├── routify-route-service     (port 8081) — route/filter persistence + Outbox
       ├── routify-audit-service     (port 8084) — append-only audit log + request replay
       ├── routify-cert-vault        (port 8085) — AES-encrypted TLS certs + cert groups
       └── routify-ai-service        (port 8086) — LLM filter/modifier evaluation (OpenAI)
routify-api-gateway  (port 8080) — Spring Cloud Gateway, hot-reloads routes from Kafka
       │ RabbitMQ RPC (AI_FILTER / AI_MODIFIER evaluation)
       └── routify-ai-service  (called directly via RabbitMQ, not via admin-api)
routify-common       (shared library) — events, DTOs, exceptions, headers, topology constants
```

**Key data flow rule:** `routify-admin-api` is the *only* gateway to backend services. Services never call each other via HTTP. All writes go via **Kafka commands**; all reads go via **RabbitMQ request/reply** (10 s timeout, `RabbitTopology.REPLY_TIMEOUT_MS`).

**Hot-reload flow:** dashboard → admin-api → Kafka command topic → route-service → Kafka event topic → api-gateway (in-memory RouteLocator updated, zero restart).

## Canonical Source Files for Messaging

All inter-service messaging constants live in `routify-common`:
- **Kafka topics** → `gr.routify.common.event.KafkaTopics` — never use string literals for topic names.
- **RabbitMQ exchanges/queues/routing-keys** → `gr.routify.common.event.RabbitTopology` — each service owns one direct exchange.
- **HTTP headers** → `gr.routify.common.web.RoutifyHeaders` — includes `X-Tenant-Id`, `X-Auth-User-Id`, `X-Correlation-Id`, `X-Auth-Tenant-Id`, `X-Auth-Role`, `X-Routify-Replay`, etc.

### Key `KafkaTopics` constants (beyond the obvious command/event pairs)
- `FILTER_EVENTS` / `FILTER_COMMANDS` — filter lifecycle, separate from route topics.
- `GATEWAY_RELOAD` — forces a full gateway reload (e.g. certificate rotation).
- `GATEWAY_CONFIG_EVENTS` — persists gateway-wide config (CORS, security headers, rate-limit) to all gateway instances.
- `AUDIT_EVENTS`, `REQUEST_TELEMETRY` — consumed by `routify-audit-service`.
- `AI_FILTER_DECISIONS`, `AI_MODIFICATION_EVENTS` — published by `routify-ai-service` after every LLM evaluation.
- `AUTH_COMMANDS` — logout blacklisting, consumed by `routify-identity-service`.
- `CERT_GROUP_EVENTS` — certificate group lifecycle, consumed by `routify-api-gateway`.
- All failed events are forwarded to DLQ topics named `<original-topic>.DLQ` (e.g. `routify.route.events.DLQ`), consumed exclusively by `routify-audit-service`.

### `routify-ai-service` communication
The API Gateway calls `routify-ai-service` via **RabbitMQ RPC** (not HTTP). Exchange: `RabbitTopology.EXCHANGE_AI_SERVICE` (`routify.ai-service`). Two queues:
- `QUEUE_AI_FILTER_EVALUATE` / `RK_AI_FILTER_EVALUATE` — filter verdict (ALLOW/BLOCK/FLAG).
- `QUEUE_AI_MODIFIER_EVALUATE` / `RK_AI_MODIFIER_EVALUATE` — request mutation (PII scrubbing, payload translation).
Reply timeouts: `AI_FILTER_REPLY_TIMEOUT_MS` = 3 500 ms; `AI_MODIFIER_REPLY_TIMEOUT_MS` = 5 000 ms.

## Project Conventions

**Java services:**
- Java 21 with **Virtual Threads** enabled (`spring.threads.virtual.enabled: true`) on all services except the reactive gateway.
- `routify-api-gateway` is **reactive** (WebFlux/Reactor/Netty) — never use blocking code there.
- Exceptions extend the **sealed** `RoutifyException` hierarchy (`NotFound`, `Conflict`, `Validation`, `BadRequest`, `Unauthorized`, `Forbidden`, `RateLimitExceeded`, `QuotaExceeded`, `GatewayError`, `HeuristicError`) — never throw raw `RuntimeException`.
- Use **MapStruct** for DTO↔entity mappings (annotation processor configured in parent `pom.xml`). Lombok + MapStruct binding order matters: `lombok-mapstruct-binding` is declared explicitly.
- All Kafka producers use `acks=all` + idempotent mode. Kafka writes from `route-service` go through the **Transactional Outbox** pattern (`OutboxPoller` polls every 250 ms, retries failed after 30 s, max 5 attempts).
- Database migrations use **Flyway** (`classpath:db/migration`). `ddl-auto: validate` — never `update`. Each service uses its own schema: `routify` (route-service), `routify_identity` (identity-service), `routify_audit` (audit-service), `routify_cert` (cert-vault).
- Write endpoints that dispatch Kafka commands return `AsyncAcknowledgement` (HTTP 202) from `gr.routify.common.web.AsyncAcknowledgement`.
- Actuator management ports are `9080`–`9086` (app port + 1000). Prometheus metrics scraped there.
- Metrics are registered in `RoutifyMetrics` (from `routify-common`) under the `routify.*` namespace.
- Sensitive DTO fields (secrets, passwords, API keys) are masked via `@SensitiveField` annotation + `Sensitive.maskFields(dto)` before returning to clients. On incoming writes, check `Sensitive.isMasked(value)` before overwriting stored secrets.

**`routify-common` shared utilities (beyond events/topics):**
- `gr.routify.common.client.AmqpServiceClientSupport` — base class for all RabbitMQ request/reply clients; extend it and call `rpc(routingKey, request, TypeReference)`.
- `gr.routify.common.security.SecurityContext` — thread-local holding `userId`, `tenantId`, `correlationId`, `role` for the current request; set by the JWT filter in each service.
- `gr.routify.common.security.RedisKeys` — centralised Redis key prefixes (e.g. `BLOCKLIST_PREFIX = "routify:token:blocklist:"`).
- `gr.routify.common.config.SecretValidator` — validates required secrets at startup via `routify.required-secrets` config property.
- `gr.routify.common.domain.FilterType` — enum of all gateway filter types (keep in sync with TypeScript `FilterType` union in dashboard and every `*GatewayFilterFactory` in the gateway).

**Frontend (`routify-dashboard`):**
- Feature code lives in `src/modules/<feature>/`. Shared primitives go in `src/components/ui/`.
- All HTTP calls use the single `apiClient` (Axios) in `src/api/client.ts` — it handles JWT injection, `X-Tenant-Id` header, and the 401→refresh lock.
- Access token stored in **Zustand** memory only (`src/store/authStore.ts`); refresh token is an **HttpOnly cookie** — JS never reads it.
- Server state managed by **TanStack Query**; client-only state by **Zustand**.
- Forms use **React Hook Form + Zod**.
- Route topology editor uses **`@xyflow/react`** (`src/modules/routes/`, `src/modules/workflow-builder/`).
- Real-time gateway events are delivered via **WebSocket** through `WebSocketProvider` (`src/components/WebSocketProvider.tsx`). `wsStore` (`src/store/wsStore.ts`) holds connection status, recent events, circuit-breaker state, and live metrics.
- `src/modules/ai/` — AI filter and AI modifier stats pages. `src/modules/workspaces/` — workspace (tenant) management.

## Developer Workflows

### Start infrastructure (required before any service)
```bash
docker compose --env-file .env up -d
# PostgreSQL :5432, Redis :6379, Kafka :9092, RabbitMQ :5672/:15672
```

### Build all Java modules
```bash
mvn clean package -DskipTests
# Build a single service (with its dependencies):
mvn clean package -pl routify-route-service -am -DskipTests
```

### Run services locally
Use the pre-configured IntelliJ run configurations in `.run/` (`Routify All Services`, `Routify Full Stack`, per-service configs).  
Or from CLI: `java -jar routify-<service>/target/routify-<service>-1.0.1-SNAPSHOT.jar`

### Frontend development
```bash
cd routify-dashboard
npm install
npm run dev           # requires backend running
npm run dev:mock      # MSW offline mode — no backend needed
```
Mock handlers live in `src/mocks/handlers/`. Mock mode is enabled by `VITE_MOCK=true`.

### Full containerised stack
```bash
mvn clean package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
# Dashboard served on :3000
```

### Reset all data
```bash
./reset-data.sh                # wipe and restart infra
./reset-data.sh --no-restart   # wipe only
./reset-data.sh --skip-grafana # keep Grafana dashboards
```

## Environment / Secrets

Copy `.env.example` → `.env`. Required variables: `DB_PASS`, `RABBITMQ_PASS`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `CERT_VAULT_ENCRYPTION_KEY`. For the AI service: `OPENAI_API_KEY` (fail-fast at startup if absent). Services validate required secrets at boot via `routify.required-secrets` config property.

## Service Ports Quick Reference

| Service | App | Actuator |
|---|---|---|
| routify-api-gateway | 8080 | 9080 |
| routify-route-service | 8081 | 9081 |
| routify-admin-api | 8082 | 9082 |
| routify-identity-service | 8083 | 9083 |
| routify-audit-service | 8084 | 9084 |
| routify-cert-vault | 8085 | 9085 |
| routify-ai-service | 8086 | 9086 |
| routify-dashboard (dev) | 5173 | — |

