# routify-admin-api

Backend-for-Frontend (BFF) service that powers the `routify-dashboard`. It is the **sole** entry point for all management operations — no other service exposes a management HTTP API.

## Responsibilities

- **Route & filter management** — dispatches commands to `routify-route-service` via Kafka; queries via RabbitMQ
- **User & tenant management** — dispatches commands to `routify-identity-service` via Kafka; queries via RabbitMQ
- **Certificate management** — dispatches commands to `routify-cert-vault` via Kafka; queries via RabbitMQ
- **Audit log & request replay** — queries `routify-audit-service` via RabbitMQ
- **AI policy testing** — proxies "Test Policy" / "Test Modification" dry-runs to `routify-ai-service` via RabbitMQ (no REST calls to ai-service)
- **Gateway config** — reads/writes CORS, security headers, rate-limit policies, circuit-breaker config via RabbitMQ
- **Real-time push** — broadcasts domain events to the dashboard via **SSE** (`GET /api/v1/admin/events`) and **WebSocket/STOMP** (`/ws/websocket`, `/ws`)
- **JWT validation** — validates RS256 tokens for all incoming requests

## Module Info

| Property | Value |
|---|---|
| Artifact | `io.routify:routify-admin-api` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8082` |
| Actuator port | `9082` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST API |
| `spring-boot-starter-websocket` | STOMP WebSocket broker (`/ws`) |
| `spring-boot-starter-security` | JWT-secured endpoints |
| `spring-kafka` | Publish commands; consume events for SSE/WS broadcast |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for all queries |
| `spring-boot-starter-data-redis` | Response caching |
| `resilience4j-spring-boot3` | Circuit breaker + time limiter per downstream service |
| `jjwt-*` | JWT parsing & validation |
| `routify-common` | Shared DTOs, events, messaging base classes |

## REST API

All endpoints require a valid Bearer JWT. Base prefix: `/api/v1/`.

### Auth (`/api/v1/auth`)
Proxied to `routify-identity-service` via RabbitMQ — the dashboard never calls identity-service directly.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/auth/login` | Issue JWT tokens |
| `POST` | `/api/v1/auth/refresh` | Refresh access token |
| `POST` | `/api/v1/auth/logout` | Revoke tokens (publishes `AUTH_COMMANDS` to Kafka) |
| `POST` | `/api/v1/auth/change-password` | Self password change |

### Routes (`/api/v1/admin/routes`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/routes` | List routes (paged) |
| `GET` | `/api/v1/admin/routes/{id}` | Get route |
| `POST` | `/api/v1/admin/routes` | Create route → HTTP 202 |
| `PUT` | `/api/v1/admin/routes/{id}` | Update route → HTTP 202 |
| `DELETE` | `/api/v1/admin/routes/{id}` | Delete route → HTTP 202 |
| `POST` | `/api/v1/admin/routes/{id}/activate` | Activate → HTTP 202 |
| `POST` | `/api/v1/admin/routes/{id}/deactivate` | Deactivate → HTTP 202 |
| `POST` | `/api/v1/admin/routes/{id}/clone` | Clone route (sync RPC) |
| `POST` | `/api/v1/admin/routes/{id}/filters` | Attach filter → HTTP 202 |
| `DELETE` | `/api/v1/admin/routes/{id}/filters/{filterId}` | Detach filter → HTTP 202 |

### Filters (`/api/v1/admin/filters`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/filters` | List filters (paged) |
| `GET` | `/api/v1/admin/filters/{id}` | Get filter |
| `POST` | `/api/v1/admin/filters` | Create filter → HTTP 202 |
| `PUT` | `/api/v1/admin/filters/{id}` | Update filter → HTTP 202 |
| `DELETE` | `/api/v1/admin/filters/{id}` | Delete filter → HTTP 202 |

### Users & Tenants

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/users` | List users (paged) |
| `POST` | `/api/v1/admin/users` | Create user → HTTP 202 |
| `PUT` | `/api/v1/admin/users/{id}` | Update user → HTTP 202 |
| `DELETE` | `/api/v1/admin/users/{id}` | Delete user → HTTP 202 |
| `GET` | `/api/v1/admin/tenants` | List tenants (paged) |
| `GET` | `/api/v1/admin/tenants/workspaces` | Active workspaces (login dropdown) |
| `POST` | `/api/v1/admin/tenants` | Create tenant |
| `PUT` | `/api/v1/admin/tenants/{id}` | Update tenant |
| `POST` | `/api/v1/admin/tenants/{id}/suspend` | Suspend tenant |
| `POST` | `/api/v1/admin/tenants/{id}/reactivate` | Reactivate tenant |

### Audit (`/api/v1/admin/audit`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/audit/events` | Event log (paged) |
| `GET` | `/api/v1/admin/audit/events/route/{routeId}` | Events for a route |
| `GET` | `/api/v1/admin/audit/events/filter/{filterId}` | Events for a filter |
| `GET` | `/api/v1/admin/audit/requests` | Request log (paged) |
| `GET` | `/api/v1/admin/audit/requests/stats/{routeId}` | Per-route request stats |
| `GET` | `/api/v1/admin/audit/replay/failed` | Failed replays |
| `GET` | `/api/v1/admin/audit/replay/pending` | Pending replays |
| `GET` | `/api/v1/admin/audit/replay/stats` | Replay stats |
| `POST` | `/api/v1/admin/audit/replay/{id}` | Replay single request |
| `POST` | `/api/v1/admin/audit/replay/bulk` | Bulk replay |

### Certificates & Cert Groups

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/certificates` | List certs |
| `GET` | `/api/v1/admin/certificates/{id}` | Get cert |
| `POST` | `/api/v1/admin/certificates` | Upload cert → HTTP 202 |
| `DELETE` | `/api/v1/admin/certificates/{id}` | Delete cert → HTTP 202 |
| `GET` | `/api/v1/admin/cert-groups` | List cert groups |
| `POST` | `/api/v1/admin/cert-groups` | Create cert group → HTTP 202 |
| `PUT` | `/api/v1/admin/cert-groups/{id}` | Update cert group → HTTP 202 |
| `DELETE` | `/api/v1/admin/cert-groups/{id}` | Delete cert group → HTTP 202 |

### Gateway (`/api/v1/admin/gateway`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/gateway/status` | Live gateway health + route count |
| `POST` | `/api/v1/admin/gateway/reload` | Force full gateway reload |
| `GET/PUT` | `/api/v1/admin/gateway/cors` | CORS config |
| `GET/PUT` | `/api/v1/admin/gateway/security-headers` | Security headers config |
| `GET/PUT` | `/api/v1/admin/gateway/rate-limit-policies` | Global rate-limit policies |
| `GET/PUT` | `/api/v1/admin/gateway/circuit-breaker` | Circuit-breaker config |
| `GET` | `/api/v1/admin/gateway/circuit-breaker/states` | Live CB states |
| `GET/PUT` | `/api/v1/admin/gateway/resilience` | Resilience config |
| `GET/PUT` | `/api/v1/admin/gateway/auth-providers` | Auth provider config |
| `GET` | `/api/v1/admin/gateway/tls` | TLS overview |
| `GET` | `/api/v1/admin/gateway/tls/certificates` | In-memory cert registry snapshot |
| `GET/PUT` | `/api/v1/admin/gateway/proxy` | Proxy settings |
| `GET/PUT` | `/api/v1/admin/gateway/http-client` | HTTP client settings |
| `GET/PUT` | `/api/v1/admin/gateway/global-filters` | Global filter chain |
| `GET/PUT` | `/api/v1/admin/gateway/tenant-isolation` | Tenant isolation config |

### AI (`/api/v1/admin/ai-filter`, `/api/v1/admin/ai-modifier`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/admin/ai-filter/test-policy` | Dry-run AI filter policy |
| `POST` | `/api/v1/admin/ai-modifier/test-modification` | Dry-run AI modifier |

### Dashboard (`/api/v1/admin`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/stats` | Platform-wide stats |
| `GET` | `/api/v1/admin/events` | SSE stream (`text/event-stream`) |
| `GET` | `/api/v1/admin/dashboard/gateway-status` | Gateway status summary |

### WebSocket / STOMP

Connect to `/ws/websocket` (raw WS) or `/ws` (SockJS fallback). Subscribe to:
- `/topic/events` — all domain events (route, filter, cert, tenant, user, audit, gateway config)
- `/topic/metrics` — live gateway metrics
- `/topic/audit` — live audit entries as they are persisted

## Messaging

### Kafka — published (commands)

| Topic constant | Topic name | Consumer |
|---|---|---|
| `ROUTE_COMMANDS` | `routify.route.commands` | `routify-route-service` |
| `FILTER_COMMANDS` | `routify.filter.commands` | `routify-route-service` |
| `USER_COMMANDS` | `routify.user.commands` | `routify-identity-service` |
| `AUTH_COMMANDS` | `routify.auth.commands` | `routify-identity-service` |
| `CERT_COMMANDS` | `routify.cert.commands` | `routify-cert-vault` |
| `GATEWAY_CONFIG_EVENTS` | `routify.gateway.config` | `routify-api-gateway` |

### Kafka — consumed (for SSE / WebSocket broadcast)

| Topic | Purpose |
|---|---|
| `routify.route.events` | Broadcast to `/topic/events` + SSE |
| `routify.filter.events` | Broadcast to `/topic/events` + SSE |
| `routify.gateway.reload` | Broadcast to `/topic/events` + SSE |
| `routify.gateway.config` | Broadcast to `/topic/events` + SSE |
| `routify.tenant.events` | Broadcast to `/topic/events` |
| `routify.user.events` | Broadcast to `/topic/events` |
| `routify.cert.events` | Broadcast to `/topic/events` |
| `routify.cert.group.events` | Broadcast to `/topic/events` |
| `routify.audit.events` | Broadcast to `/topic/audit` |

### RabbitMQ — request/reply queries (initiated)

Each downstream client is wrapped with a Resilience4j circuit breaker (named after the service) and a 5 s time limiter:

- `RouteServiceClient` → `routify-route-service` (routes, filters, stats, gateway config, clone)
- `IdentityMessagingClient` → `routify-identity-service` (users, tenants, auth login/refresh)
- `AuditMessagingClient` → `routify-audit-service` (event log, request log, replay)
- `CertVaultMessagingClient` → `routify-cert-vault` (certs, cert groups, gateway snapshot)
- `AiMessagingClient` → `routify-ai-service` (AI filter test-policy, AI modifier test)

## Building & Running

```bash
# Build
mvn clean package -pl routify-admin-api -am -DskipTests

# Run
java -jar target/routify-admin-api-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.

## Docker

```bash
docker build -t routify-admin-api .
docker run -p 8082:8082 routify-admin-api
```
