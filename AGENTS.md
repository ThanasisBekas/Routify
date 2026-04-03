# Routify — AI Agent Guide

## Architecture Overview

Routify is a **self-hosted API gateway platform** composed of 9 modules in a Maven multi-module build (`pom.xml`). The key structural insight is the **BFF + dual-messaging pattern**:

- **`routify-admin-api`** (port 8082) is the sole backend for the dashboard — it never talks directly to other services via HTTP. Write operations go as **Kafka commands**; read operations are **RabbitMQ request/reply** queries.
- **`routify-api-gateway`** (port 8080) hot-reloads routes from Kafka events with zero restart — see `DynamicRouteRefreshListener.java`.
- **`routify-common`** is a shared library (not a service) — all topic/queue constants, sealed `DomainEvent`/`CommandEvent` records, and `RoutifyException` hierarchy live here.
- **`routify-ai-service`** (port 8086) is called exclusively via **RabbitMQ RPC** (not HTTP) by `routify-api-gateway` for AI filter and modifier evaluation. It uses Spring AI → OpenAI `gpt-4o-mini` with Redis verdict caching and Resilience4j circuit breaker.

| Service | Port | App | Mgmt (Actuator) |
|---|---|---|---|
| routify-identity-service | 8083 | 8083 | 9083 |
| routify-route-service | 8081 | 8081 | 9081 |
| routify-audit-service | 8084 | 8084 | 9084 |
| routify-cert-vault | 8085 | 8085 | 9085 |
| routify-admin-api | 8082 | 8082 | 9082 |
| routify-api-gateway | 8080 | 8080 | 9080 |
| routify-ai-service | 8086 | 8086 | 9086 |

## Developer Workflows

```bash
# 1. Start infrastructure (Postgres, Redis, Kafka, RabbitMQ, Prometheus, Grafana)
docker compose --env-file .env up -d

# 2. Build all Java modules (skip tests for speed)
mvn clean package -DskipTests

# 3. Build a single module and its dependencies
mvn clean package -pl routify-route-service -am -DskipTests

# 4. Run services locally (each in a separate terminal)
java -jar routify-identity-service/target/routify-identity-service-*.jar

# 5. Run the dashboard
cd routify-dashboard && npm install && npm run dev       # needs backend
cd routify-dashboard && npm run dev:mock                 # offline (MSW)

# 6. Full containerised stack
mvn clean package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d

# 7. Reset all persistent data (wipe Docker volumes)
./reset-data.sh
./reset-data.sh --skip-grafana   # preserve Grafana dashboards
./reset-data.sh --no-restart     # wipe without restarting
```

**Required `.env` variables**: `DB_PASS`, `RABBITMQ_PASS`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `CERT_VAULT_ENCRYPTION_KEY`, `OPENAI_API_KEY`. Services fail fast at startup if any required secret is missing — enforced by `SecretValidator.java`.

## Messaging Conventions

**Kafka** (async fire-and-forget) — all topic names are constants in `KafkaTopics.java`:

| Topic | Producer | Consumer(s) |
|---|---|---|
| `routify.route.commands` | admin-api | route-service |
| `routify.filter.commands` | admin-api | route-service |
| `routify.user.commands` | admin-api | identity-service |
| `routify.auth.commands` | admin-api | identity-service (token blacklist) |
| `routify.tenant.commands` | admin-api (Kafka fallback; prefer RabbitMQ sync) | identity-service |
| `routify.cert.commands` | admin-api | cert-vault |
| `routify.route.events` | route-service (Outbox) | api-gateway hot-reload, audit-service, admin-api (SSE) |
| `routify.filter.events` | route-service (Outbox) | api-gateway filter chain rebuild |
| `routify.cert.events` | cert-vault (Outbox) | api-gateway TLS hot-reload, audit-service |
| `routify.cert.group.events` | cert-vault (Outbox) | api-gateway TLS registry sync |
| `routify.gateway.reload` | admin-api / cert-vault | api-gateway (forced reload, e.g. cert rotation) |
| `routify.gateway.config` | admin-api | all gateway instances (config reload without restart) |
| `routify.user.events` | identity-service | audit-service |
| `routify.tenant.events` | identity-service | audit-service |
| `routify.audit.events` | any service | audit-service |
| `routify.request.telemetry` | api-gateway (`GatewayTelemetryPublisher`) | audit-service |
| `routify.ai.filter.decisions` | ai-service | audit-service |
| `routify.ai.modification.events` | ai-service | audit-service |

- DLQ naming: `<original-topic>.DLQ` (typed constants in `KafkaTopics.java`). Retry policy: exponential back-off 1s→30s via `KafkaDlqErrorHandlerFactory`.

**RabbitMQ** (sync request/reply) — all exchange/queue/routing-key constants in `RabbitTopology.java`:
- Each service owns a **direct exchange** named after it (e.g. `routify.route-service`, `routify.ai-service`).
- Default reply timeout: `10_000ms`; AI filter timeout: `3_500ms`; AI modifier timeout: `5_000ms`.
- Tenant lifecycle commands (`CreateTenant`, `SuspendTenant`, etc.) travel over **RabbitMQ sync** (not Kafka) via `QUEUE_TENANTS_COMMAND` — use this for immediate confirmation.
- Gateway config saves use `QUEUE_GATEWAY_CONFIG_SAVE` (RabbitMQ sync) then broadcast a `GatewayConfigChanged` domain event via Kafka so all gateway instances reload without restart.
- Auth operations (login, refresh, password change) are handled via RabbitMQ queues on identity-service (`QUEUE_AUTH_LOGIN`, `QUEUE_AUTH_REFRESH`, `QUEUE_AUTH_CHANGE_PASSWORD`).
- RabbitMQ AMQP headers in use: `X-From-Service`, `X-Tenant-Id`, `X-User-Id`, `X-Command`, `X-Config-Section`, `X-Changed-By` — defined in `RabbitTopology.java`.

**Consumer pattern** — use Java 21 sealed-class `switch` on `DomainEvent`/`CommandEvent`:
```java
switch (event) {
    case DomainEvent.RouteActivated ra -> handleRouteActivated(ra);
    case DomainEvent.RouteDeleted rd   -> handleRouteDeleted(rd);
    // ...
}
```

## Database & Migrations

- Four PostgreSQL schemas: `routify`, `routify_identity`, `routify_audit`, `routify_cert` — created by `docker/postgres/init.sql`.
- **Flyway** manages schema per service under `src/main/resources/db/migration/`. JPA `ddl-auto` is set to `validate` (never auto-generates DDL).
- All services use the `routify` PostgreSQL user. The search path covers all four schemas.

## Java Code Patterns

- **Exceptions**: Use `RoutifyException` sealed subtypes (`NotFound`, `Conflict`, `Validation`, etc.). A `GlobalExceptionHandler` in `routify-common` maps these to standardised HTTP responses.
- **Events/Commands**: Always strongly-typed sealed records from `routify-common`. Never use raw `Map<String,Object>` on the wire. Include `eventId`/`commandId` as idempotency keys. Both `DomainEvent` and `CommandEvent` include an `Unknown` fallback subtype — always add a default case in `switch` statements to handle unknown types gracefully.
- **Virtual Threads**: All backend services enable `spring.threads.virtual.enabled: true`.
- **Actuator** runs on a separate management port (`900x`) and exposes `health,info,metrics,prometheus`. Do not expose additional endpoints.
- **Security**: JWT RS256 tokens issued by identity-service. Public key shared to all services via `JWT_PUBLIC_KEY` env var. Revoked tokens tracked in Redis under key `routify:token:blocklist:<jti>` (constant in `RedisKeys.java`).
- **Metrics**: Platform-wide Micrometer metrics are centralised in `RoutifyMetrics.java` (routify-common). All metric names use the `routify.` namespace. Use this class — do not register ad-hoc metrics with string literals.
- **Gateway filters**: All custom filters implement `GatewayFilterFactory` and live in `routify-api-gateway/.../filter/`. Existing factories include AI (`AiGatewayFilterFactory`, `AiModifierGatewayFilterFactory`), auth (`JwtAuthGatewayFilterFactory`, `MtlsAuthGatewayFilterFactory`, `OAuth2TokenIntrospectGatewayFilterFactory`, `ApiKeyAuthGatewayFilterFactory`), rate limiting (`FixedWindowRateLimitGatewayFilterFactory`, `SlidingWindowRateLimitGatewayFilterFactory`), and transform filters (`JoltTransformGatewayFilterFactory`, `JsonataTransformGatewayFilterFactory`, `SpelTransformGatewayFilterFactory`). Never duplicate filter logic — extend an existing factory.

## Frontend Conventions

- **Feature modules** live under `src/modules/` — current modules: `auth`, `routes`, `filters`, `users`, `certificates`, `audit`, `gateway`, `settings`, `ai`, `workspaces`, `workflow-builder`.
- **API clients** in `src/api/` use the single Axios instance from `src/api/client.ts`. That instance auto-injects `Authorization: Bearer <token>` and `X-Tenant-Id` on every request, and handles 401 → refresh with a queued-subscriber lock.
- **Auth state**: Access token is **memory-only** in Zustand (`authStore.ts`); refresh token is an **HttpOnly cookie** — never read by JS. The `persist` middleware is configured with `partialize: () => ({})` intentionally.
- **Mock mode**: `npm run dev:mock` (sets `VITE_MOCK=true`) activates MSW handlers in `src/mocks/handlers/`. No proxy is configured in this mode — see `vite.config.ts`.
- **Dev proxy**: `vite.config.ts` proxies `/api`, `/ws`, and `/sse` to `localhost:8082` (admin-api) when not in mock mode.

## Key Files

| File | Purpose |
|---|---|
| `routify-common/.../event/KafkaTopics.java` | All Kafka topic name constants |
| `routify-common/.../event/RabbitTopology.java` | All RabbitMQ exchange/queue/routing-key constants |
| `routify-common/.../event/DomainEvent.java` | Sealed Kafka event records |
| `routify-common/.../event/CommandEvent.java` | Sealed Kafka command records |
| `routify-common/.../exception/RoutifyException.java` | Exception hierarchy |
| `routify-common/.../kafka/KafkaDlqErrorHandlerFactory.java` | Standard Kafka DLQ error handler |
| `routify-common/.../observability/RoutifyMetrics.java` | Platform-wide Micrometer metrics |
| `routify-common/.../security/RedisKeys.java` | Shared Redis key namespace constants |
| `routify-api-gateway/.../routing/DynamicRouteRefreshListener.java` | Hot-reload entry point |
| `routify-api-gateway/.../filter/` | All gateway filter factory implementations |
| `routify-api-gateway/.../telemetry/GatewayTelemetryPublisher.java` | Publishes request telemetry to Kafka |
| `routify-route-service/.../outbox/OutboxPoller.java` | Transactional outbox pattern |
| `routify-audit-service/.../replay/` | Failed-event replay logic |
| `routify-dashboard/src/api/client.ts` | Axios instance with auth interceptors |
| `docker/postgres/init.sql` | Schema + extension initialisation |

