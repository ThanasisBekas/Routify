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
- **HTTP headers** → `gr.routify.common.web.RoutifyHeaders` — includes `X-Tenant-Id`, `X-Auth-User-Id`, `X-Correlation-Id`, `X-Auth-Tenant-Id`, `X-Auth-Role`, `X-Routify-Replay`, `X-Auth-Email`, `X-Auth-Type`, `X-Api-Key`, `X-Route-Version`, etc.

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

> Note: `routify-ai-service` has **no REST controllers**. The dashboard "Test Policy" dry-run feature hits `POST /api/v1/admin/ai-filter/test-policy` on `routify-admin-api`, which proxies the call to `routify-ai-service` via `AiMessagingClient` over RabbitMQ (same `EXCHANGE_AI_SERVICE`). `AI_MODIFIER` dry-runs go to `POST /api/v1/admin/ai-modifier/test-modification`. The gateway always uses RabbitMQ via `AiGatewayFilterFactory` / `AiModifierGatewayFilterFactory`; blocking `sendAndReceive` is offloaded to `Schedulers.boundedElastic()` to avoid blocking the Netty event loop. For air-gapped/local deployments, swap `spring-ai-starter-model-openai` → `spring-ai-starter-model-ollama` in `routify-ai-service/pom.xml` — no Java logic changes required (`ChatClient` is provider-agnostic).

### Additional `RabbitTopology` queues (beyond obvious CRUD queries)
- **Auth** (all on `EXCHANGE_IDENTITY_SERVICE`): `QUEUE_AUTH_LOGIN` / `QUEUE_AUTH_REFRESH` / `QUEUE_AUTH_CHANGE_PASSWORD` / `QUEUE_USERS_CHANGE_PASSWORD` — admin-api proxies all auth operations over RabbitMQ to identity-service. The dashboard hits `/api/v1/auth/*` on admin-api, never directly on identity-service.
- **Route extras**: `QUEUE_ROUTES_CLONE` (`routes.clone`) — sync RPC for route cloning; `QUEUE_ROUTE_STATS` (`route.stats`); `QUEUE_GATEWAY_CONFIG_GET` / `QUEUE_GATEWAY_CONFIG_SAVE` — gateway-wide CORS/security/rate-limit config stored by route-service.
- **Workspace**: `QUEUE_TENANTS_LIST_ACTIVE` (`tenants.list-active`) — active workspace list for login dropdown; `QUEUE_TENANTS_COMMAND` (`tenants.command`) — sync suspend/reactivate tenant.
- **Gateway**: `QUEUE_GATEWAY_CERT_REGISTRY` (`gateway.cert.registry`) — gateway serves a snapshot of its live in-memory `CertificateRegistry` (fingerprint, expiry, source, status per logical cert ID).
- **Audit** (on `EXCHANGE_AUDIT_SERVICE`): `QUEUE_AUDIT_EVENTS_QUERY` / `QUEUE_AUDIT_REQUESTS_QUERY` / `QUEUE_AUDIT_REQUESTS_STATS` — event log, request log, and per-route request stats queries. Audit replay queues: `QUEUE_AUDIT_REPLAY_FAILED_QUERY`, `QUEUE_AUDIT_REPLAY_PENDING_QUERY`, `QUEUE_AUDIT_REPLAY_STATS`, `QUEUE_AUDIT_REPLAY_SINGLE`, `QUEUE_AUDIT_REPLAY_BULK` — served by audit-service, consumed by admin-api's `AdminReplayController` (`/api/v1/admin/audit/replay`).
- **AI audit** (on `EXCHANGE_AUDIT_SERVICE`): `QUEUE_AUDIT_AI_FILTER_STATS` (`audit.ai-filter.stats`) and `QUEUE_AUDIT_AI_FILTER_QUERY` (`audit.ai-filter.query`) — serve AI filter decision analytics to admin-api.
- **Cert-vault** (on `EXCHANGE_CERT_VAULT`): `QUEUE_CERTS_QUERY` / `QUEUE_CERTS_GET` / `QUEUE_CERTS_ACTIVE_LIST` / `QUEUE_CERTS_STATS` / `QUEUE_CERTS_GATEWAY_SNAPSHOT` / `QUEUE_CERT_GROUPS_QUERY` / `QUEUE_CERT_GROUPS_GET` / `QUEUE_CERT_GROUPS_MEMBERS` — cert and cert-group queries. `QUEUE_CERTS_FETCH_MATERIAL` (`certs.fetch-material`) is **internal only** — gateway fetches decrypted PEM material for its in-memory `CertificateRegistry`; never call this from admin-api.
- **RabbitMQ message headers**: `RabbitTopology.HEADER_FROM_SERVICE` (`X-From-Service`), `HEADER_CONFIG_SECTION`, `HEADER_CHANGED_BY`, `HEADER_TENANT_ID`, `HEADER_USER_ID`, `HEADER_COMMAND` — set by `AmqpServiceClientSupport` automatically.

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
- All `routify-admin-api` REST endpoints use the `/api/v1/admin/` prefix (e.g. `/api/v1/admin/routes`, `/api/v1/admin/gateway`, `/api/v1/admin/audit/replay`). Auth endpoints use `/api/v1/auth/`. Each downstream messaging client in admin-api (e.g. `RouteServiceClient`, `IdentityMessagingClient`) is wrapped with a **Resilience4j circuit breaker** named after the service (e.g. `"route-service"`, `"identity-service"`) with a 5 s time limiter.

**`routify-common` shared utilities (beyond events/topics):**
- `gr.routify.common.client.AmqpServiceClientSupport` — base class for all RabbitMQ request/reply clients; extend it and call `rpc(routingKey, request, TypeReference)`. Also exposes `send()` for fire-and-forget.
- `gr.routify.common.client.KafkaServiceClientSupport` — base class for Kafka producers; provides `publish()` (async), `publishSync()` (broker-ACK), `publishCommand()` (standard command envelope), and `publishEvent()` (domain event) helpers.
- `gr.routify.common.event.CommandEvent` — **sealed interface** (Java 21) representing every write command sent over Kafka. All concrete command types are `record`s that implement it (e.g. `CommandEvent.CreateRoute`, `CommandEvent.UpdateFilter`). Deserialise with `objectMapper.readValue(json, CommandEvent.class)` and use a `switch` expression on the sealed type. The `commandId` field is the idempotency key. `CommandEvent.Unknown` is the fallback for unrecognised `"type"` discriminators.
- `gr.routify.common.event.DomainEvent` — base type for domain event payloads published on event topics.
- `gr.routify.common.event.QueryRequest` / `QueryResponse` — typed wrappers for all RabbitMQ request/reply calls. Each query variant is a nested record (e.g. `QueryRequest.AiFilterEvaluate`).
- `gr.routify.common.kafka.KafkaDlqErrorHandlerFactory` — shared DLQ `DefaultErrorHandler` with exponential back-off (1 s × 2.0, max 30 s ≈ 5 retries); deserialisation errors go straight to DLQ. Use: `factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate))`.
- `gr.routify.common.security.SecurityContext` — Java 21 **record** (`userId`, `tenantId`, `username`, `role`, `correlationId`) stored in a `ThreadLocal`; set by the JWT filter in each service. Call `SecurityContext.current()` / `SecurityContext.set()` / `SecurityContext.clear()`. Convenience helpers: `hasRole(String)`, `isSuperAdmin()`, `isTenantAdmin()`.
- `gr.routify.common.security.RedisKeys` — centralised Redis key prefixes (e.g. `BLOCKLIST_PREFIX = "routify:token:blocklist:"`).
- `gr.routify.common.config.SecretValidator` — validates required secrets at startup via `routify.required-secrets` config property.
- `gr.routify.common.domain.FilterType` — enum of all gateway filter types (keep in sync with TypeScript `FilterType` union in dashboard and every `*GatewayFilterFactory` in the gateway). Active types include `AUTH_*` (`AUTH_API_KEY`, `AUTH_BASIC`, `AUTH_JWT`, `AUTH_MTLS`, `AUTH_OAUTH2`, `AUTH_CLIENT_ID`, `AUTH_CERT_VAULT`), `DOWNSTREAM_BASIC_AUTH`, `DOWNSTREAM_BEARER_CC`, `RATE_LIMIT_FIXED_WINDOW`, `RATE_LIMIT_SLIDING_WINDOW`, `REQUEST_HEADER_MODIFY`, `RESPONSE_HEADER_MODIFY`, `AI_FILTER`, `AI_MODIFIER`, `BODY_JOLT_TRANSFORM`, `VALIDATE_JSON_SCHEMA`, `TIMEOUT`, `CONDITIONAL_ROUTE`, `USER_ID_PAYLOAD_ROUTING`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`, `API_VERSIONING`, `CORRELATION_ID`, `REQUEST_LOGGER`, `TENANT_CONTEXT`, `SECURITY_HEADERS`, `CUSTOM_METRIC`, `CUSTOM_SPEL`. Several legacy values are `@Deprecated` (no factory implementation — kept for DB compatibility only): `AUTH_NONE`, `RATE_LIMIT_TOKEN_BUCKET`, `PATH_REWRITE`, `PATH_STRIP_PREFIX`, `PATH_ADD_PREFIX`, `QUERY_PARAM_MODIFY`, `BODY_JSONATA_TRANSFORM`, `BODY_SPEL_TRANSFORM`, `VALIDATE_REGEX`, `VALIDATE_SIZE`, `CIRCUIT_BREAKER`, `RETRY`.
- `gr.routify.common.domain.TenantPlan` — enum (`FREE`, `STARTER`, `PRO`, `ENTERPRISE`) encoding `maxRoutes`, `maxFilters`, `monthlyRequestQuota` quotas enforced at the service layer.
- `gr.routify.common.domain.UserRole` / `RouteStatus` — additional domain enums in the same package.
- `gr.routify.common.web.RoutifyHeaders.resolveActor(userId, principalName)` — resolves the acting principal for audit/command attribution (resolution order: `X-User-Id` header → principal name → `"system"`).

**Frontend (`routify-dashboard`):**
- Feature code lives in `src/modules/<feature>/`. Shared primitives go in `src/components/ui/`.
- All HTTP calls use the single `apiClient` (Axios) in `src/api/client.ts` — it handles JWT injection, `X-Tenant-Id` header, and the 401→refresh lock.
- Access token stored in **Zustand** memory only (`src/store/authStore.ts`); refresh token is an **HttpOnly cookie** — JS never reads it.
- Server state managed by **TanStack Query**; client-only state by **Zustand**.
- Forms use **React Hook Form + Zod**.
- Route topology editor uses **`@xyflow/react`** (`src/modules/routes/`, `src/modules/workflow-builder/`).
- Real-time gateway events are delivered via **WebSocket/STOMP** through `WebSocketProvider` (`src/components/WebSocketProvider.tsx`). The admin-api exposes two endpoints: `GET /api/v1/admin/events` (SSE, `text/event-stream`) and a STOMP broker at `/ws` (SockJS fallback) / `/ws/websocket` (raw WS). The dashboard connects to `/ws/websocket` via raw STOMP and subscribes to `/topic/events` (domain events), `/topic/metrics` (live gateway metrics), and `/topic/audit` (live audit entries). `wsStore` (`src/store/wsStore.ts`) holds connection status, recent events, circuit-breaker state, and live metrics.
- `src/modules/ai/` — AI filter and AI modifier stats pages. `src/modules/workspaces/` — workspace (tenant) management. `src/modules/gateway/` — gateway status dashboard. `src/modules/settings/` — platform settings. `src/modules/workflow-builder/` — visual route topology editor (shares `@xyflow/react` with `src/modules/routes/`).
- Separate API modules in `src/api/`: `authApi.ts`, `routesApi.ts`, `filtersApi.ts`, `usersApi.ts`, `tenantsApi.ts`, `auditApi.ts`, `certVaultApi.ts`, `gatewayApi.ts`, `aiApi.ts`.
- Key env vars: `VITE_API_BASE_URL` (defaults to `http://localhost:8082`) — used by both `apiClient` and as the base for the derived WebSocket URL. `VITE_MOCK=true` enables MSW mode. In dev the Vite proxy forwards `/api`, `/ws`, and `/sse` to `localhost:8082` so `VITE_API_BASE_URL` can be left unset. Set in `.env.local`.

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
Or from CLI: `java -jar routify-<service>/target/routify-<service>-1.0.2-SNAPSHOT.jar`

### Frontend development
```bash
cd routify-dashboard
npm install
npm run dev           # requires backend running
npm run dev:mock      # MSW offline mode — no backend needed
npm run build         # production build → dist/
npm run lint          # ESLint
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

