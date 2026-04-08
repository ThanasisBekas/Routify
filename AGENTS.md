# Routify — AI Agent Guide

## Architecture

Routify is a zero-downtime API Gateway platform. Maven multi-module monorepo (Java 25, Spring Boot 4.0.5) + React/Vite frontend.

**Data flow:** Dashboard → `admin-api` (BFF) → Kafka commands → owning service → Kafka events → `api-gateway` hot-reloads in-memory routes. All queries use RabbitMQ RPC (Direct Reply-To). No direct HTTP between backend services.

| Module | Role | Port | Runtime |
|---|---|---|---|
| `routify-common` | Shared library (events, DTOs, exceptions, constants) | — | — |
| `routify-admin-api` | BFF — sole HTTP API for dashboard | 8082 | Web (blocking, Virtual Threads) |
| `routify-api-gateway` | Spring Cloud Gateway — dynamic routing | 8080 | **Reactive (WebFlux)** — never block |
| `routify-route-service` | Route/filter persistence + Transactional Outbox | 8081 | Web (Virtual Threads) |
| `routify-identity-service` | JWT auth, users, tenants, API keys | 8083 | Web (Virtual Threads) |
| `routify-audit-service` | Immutable audit log, request replay | 8084 | Web (Virtual Threads) |
| `routify-cert-vault` | AES-encrypted TLS cert storage | 8085 | Web (Virtual Threads) |
| `routify-ai-service` | LLM filter/modifier via RabbitMQ RPC (no REST) | 8086 | Web (Virtual Threads) |
| `routify-gitops-agent` | GitOps sync agent | 8087 | Web (Virtual Threads) |
| `routify-dashboard` | React 19 + Vite + TailwindCSS 4 | 5173 | — |

**Database:** Single PostgreSQL 17 instance, separate schemas per service (`routify`, `routify_identity`, `routify_audit`, `routify_cert`). Flyway migrations in each service at `classpath:db/migration`; `ddl-auto: validate`.

## Messaging Topology

- **Kafka** = commands (writes) + domain events. Topic/queue names are constants in `KafkaTopics` — never use string literals.
- **RabbitMQ** = synchronous request/reply queries. Exchange/queue/routing-key constants in `RabbitTopology` — never use string literals.
- Kafka command flow: `admin-api` publishes `CommandEvent` records → owning service consumes and executes → publishes domain events via Outbox.
- Write operations return **HTTP 202** with `AsyncAcknowledgement`; the dashboard receives the result via WebSocket/SSE.

### Adding a new command
1. Add a `record` to the `CommandEvent` sealed interface in `routify-common` with `@JsonSubTypes.Type` annotation.
2. Add a topic constant to `KafkaTopics` if needed (or reuse existing).
3. Publish from `admin-api` using `KafkaServiceClientSupport.publishCommand()`.
4. Consume in the owning service with `switch` on the sealed type.

### Adding a new RabbitMQ query
1. Add exchange/queue/routing-key constants to `RabbitTopology`.
2. In the responding service, add a `@RabbitListener` on the queue.
3. In the calling service, extend `AmqpServiceClientSupport` and call `rpc(routingKey, request, TypeReference)`.

## Build & Run

```bash
# Infrastructure
docker compose --env-file .env up -d          # Postgres, Redis, Kafka, RabbitMQ, Tempo, Prometheus, Grafana

# Build all Java modules
mvn clean package -DskipTests

# Build a single module (with dependencies)
mvn clean package -pl routify-route-service -am -DskipTests

# Frontend
cd routify-dashboard && npm install && npm run dev       # with backend
cd routify-dashboard && npm run dev:mock                 # mock mode (no backend)

# Full containerised stack
docker compose -f docker-compose.yml -f docker-compose.app.yml --env-file .env up -d
```

**Integration tests** (`*IT.java`) are disabled globally (`<skipITs>true</skipITs>`) due to Testcontainers + Docker Engine 29.x incompatibility. Re-enable: `mvn verify -DskipITs=false`.

**Environment:** Copy `environments/.env.develop` → `.env` at project root. Secrets (`DB_PASS`, `JWT_PRIVATE_KEY`, etc.) come from env files only. IntelliJ run configs auto-load `environments/.env.develop`.

## Code Conventions

### Exceptions
Always use `RoutifyException` subtypes (`NotFound`, `Conflict`, `Validation`, `BadRequest`, `Unauthorized`, `Forbidden`, `RateLimitExceeded`, `QuotaExceeded`, `GatewayError`). Never throw raw `RuntimeException`. Error responses use RFC 9457 ProblemDetail via `GlobalExceptionHandler`.

### Entity ↔ DTO mappings
Use MapStruct (`@Mapper(componentModel = "spring")`). See `routify-route-service/.../mapper/RouteMapper.java` for the pattern. Annotation processors: Lombok → MapStruct → lombok-mapstruct-binding (order matters in `pom.xml`).

### Messaging clients
- **Kafka producers**: extend `KafkaServiceClientSupport` — provides `publishCommand()`, `publishEvent()`, `publish()`, `publishSync()`.
- **RabbitMQ RPC clients**: extend `AmqpServiceClientSupport` — provides `rpc()`, `send()`. Each client in `admin-api` is wrapped with Resilience4j circuit breaker + 5s time limiter.

### Gateway module (reactive)
`routify-api-gateway` runs on WebFlux/Netty. **Never** use blocking calls (`Thread.sleep`, blocking I/O, JPA). Offload blocking work to `Schedulers.boundedElastic()`. Each `FilterType` enum maps to a `*GatewayFilterFactory` class.

### Frontend (routify-dashboard)
- **API calls**: always use `apiClient` from `src/api/client.ts` — handles JWT injection, `X-Tenant-Id` header, and 401→refresh. Never create new Axios instances.
- **Server state**: TanStack Query. **Client state**: Zustand (`authStore`, `wsStore`).
- **Forms**: React Hook Form + Zod for validation.
- **Error handling**: `extractApiError()` from `src/lib/utils.ts` — never create separate error utils.
- **Auth**: access token in Zustand memory only (never localStorage); refresh token is HttpOnly cookie.
- **Real-time**: WebSocket/STOMP via `WebSocketProvider` → subscribes to `/topic/events`, `/topic/metrics`, `/topic/audit`.
- **Mock mode**: MSW handlers in `src/mocks/handlers/` — `npm run dev:mock`.
- **Styling**: TailwindCSS 4 utilities + `cn()` helper from `src/lib/utils.ts`.
- **Feature modules**: each feature lives in `src/modules/<feature>/` (routes, filters, audit, certificates, ai, gateway, users, workspaces, settings, workflow-builder).

## Module-Level Agent Guides

Each module has its own `AGENTS.md` with detailed package layout, patterns, and extension recipes:

| Module | Guide |
|---|---|
| Shared library | [`routify-common/AGENTS.md`](routify-common/AGENTS.md) |
| Admin API (BFF) | [`routify-admin-api/AGENTS.md`](routify-admin-api/AGENTS.md) |
| API Gateway | [`routify-api-gateway/AGENTS.md`](routify-api-gateway/AGENTS.md) |
| Route Service | [`routify-route-service/AGENTS.md`](routify-route-service/AGENTS.md) |
| Identity Service | [`routify-identity-service/AGENTS.md`](routify-identity-service/AGENTS.md) |
| Audit Service | [`routify-audit-service/AGENTS.md`](routify-audit-service/AGENTS.md) |
| Certificate Vault | [`routify-cert-vault/AGENTS.md`](routify-cert-vault/AGENTS.md) |
| AI Service | [`routify-ai-service/AGENTS.md`](routify-ai-service/AGENTS.md) |
| GitOps Agent | [`routify-gitops-agent/AGENTS.md`](routify-gitops-agent/AGENTS.md) |
| Dashboard (React) | [`routify-dashboard/AGENTS.md`](routify-dashboard/AGENTS.md) |

## Key Files

| What | Where |
|---|---|
| Kafka topic constants | `routify-common/.../event/KafkaTopics.java` |
| RabbitMQ topology constants | `routify-common/.../event/RabbitTopology.java` |
| Command event definitions | `routify-common/.../event/CommandEvent.java` |
| Domain event definitions | `routify-common/.../event/DomainEvent.java` |
| Exception hierarchy | `routify-common/.../exception/RoutifyException.java` |
| Global error handler | `routify-common/.../exception/GlobalExceptionHandler.java` |
| Kafka client base class | `routify-common/.../client/KafkaServiceClientSupport.java` |
| RabbitMQ client base class | `routify-common/.../client/AmqpServiceClientSupport.java` |
| MapStruct mapper example | `routify-route-service/.../mapper/RouteMapper.java` |
| Frontend API client | `routify-dashboard/src/api/client.ts` |
| Auth store | `routify-dashboard/src/store/authStore.ts` |
| DB schema init | `docker/postgres/init.sql` |

