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
       ├── routify-audit-service     (port 8084) — append-only audit log
       ├── routify-cert-vault        (port 8085) — AES-encrypted TLS certs
       └── routify-ai-service        (port 8086) — LLM filter evaluation (OpenAI)
routify-api-gateway  (port 8080) — Spring Cloud Gateway, hot-reloads routes from Kafka
routify-common       (shared library) — events, DTOs, exceptions, headers, topology constants
```

**Key data flow rule:** `routify-admin-api` is the *only* gateway to backend services. Services never call each other via HTTP. All writes go via **Kafka commands**; all reads go via **RabbitMQ request/reply** (10 s timeout, `RabbitTopology.REPLY_TIMEOUT_MS`).

**Hot-reload flow:** dashboard → admin-api → Kafka command topic → route-service → Kafka event topic → api-gateway (in-memory RouteLocator updated, zero restart).

## Canonical Source Files for Messaging

All inter-service messaging constants live in `routify-common`:
- **Kafka topics** → `gr.routify.common.event.KafkaTopics` — never use string literals for topic names.
- **RabbitMQ exchanges/queues/routing-keys** → `gr.routify.common.event.RabbitTopology` — each service owns one direct exchange.
- **HTTP headers** → `gr.routify.common.web.RoutifyHeaders` — includes `X-Tenant-Id`, `X-Auth-User-Id`, `X-Correlation-Id`, `X-Routify-Replay`, etc.

## Project Conventions

**Java services:**
- Java 21 with **Virtual Threads** enabled (`spring.threads.virtual.enabled: true`) on all services except the reactive gateway.
- `routify-api-gateway` is **reactive** (WebFlux/Reactor/Netty) — never use blocking code there.
- Exceptions extend the **sealed** `RoutifyException` hierarchy (`NotFound`, `Conflict`, `Validation`, `BadRequest`, `Forbidden`, etc.) — never throw raw `RuntimeException`.
- Use **MapStruct** for DTO↔entity mappings (annotation processor configured in parent `pom.xml`). Lombok + MapStruct binding order matters: `lombok-mapstruct-binding` is declared explicitly.
- All Kafka producers use `acks=all` + idempotent mode. Kafka writes from `route-service` go through the **Transactional Outbox** pattern (`OutboxPoller` polls every 250 ms, retries failed after 30 s, max 5 attempts).
- Database migrations use **Flyway** (`classpath:db/migration`, schema `routify`). `ddl-auto: validate` — never `update`.
- Actuator management ports are `9080`–`9086` (app port + 1000). Prometheus metrics scraped there.
- Metrics are registered in `RoutifyMetrics` (from `routify-common`) under the `routify.*` namespace.

**Frontend (`routify-dashboard`):**
- Feature code lives in `src/modules/<feature>/`. Shared primitives go in `src/components/ui/`.
- All HTTP calls use the single `apiClient` (Axios) in `src/api/client.ts` — it handles JWT injection, `X-Tenant-Id` header, and the 401→refresh lock.
- Access token stored in **Zustand** memory only (`src/store/authStore.ts`); refresh token is an **HttpOnly cookie** — JS never reads it.
- Server state managed by **TanStack Query**; client-only state by **Zustand**.
- Forms use **React Hook Form + Zod**.
- Route topology editor uses **`@xyflow/react`** (`src/modules/routes/`, `src/modules/workflow-builder/`).

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

