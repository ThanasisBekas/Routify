# routify-admin-api

Backend-for-Frontend (BFF) service that powers the `routify-dashboard`. It is the single entry point for all management operations, aggregating data from `routify-route-service`, `routify-identity-service`, `routify-audit-service`, and `routify-cert-vault`.

## Responsibilities

- **Route management** — proxies route/filter CRUD commands to `routify-route-service` via Kafka; queries route data via RabbitMQ
- **User & tenant management** — sends commands to `routify-identity-service` via Kafka; queries user/tenant data via RabbitMQ
- **Audit log** — retrieves audit records from `routify-audit-service` via RabbitMQ
- **Certificate management** — sends certificate upload/delete commands to `routify-cert-vault` via Kafka; queries certificates via RabbitMQ
- **Real-time updates** — pushes route status changes to the dashboard via **Server-Sent Events (SSE)**
- **JWT validation** — validates RS256 tokens for all incoming requests using Spring Security
- **Redis caching** — short-lived caches for frequently-read data (route lists, user lists)

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-admin-api` |
| Version | `2.0.0-SNAPSHOT` |
| Default port | `8085` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST API for the dashboard |
| `spring-boot-starter-webflux` | SSE streaming (`SseEmitter`) |
| `spring-boot-starter-websocket` | WebSocket support for real-time events |
| `spring-boot-starter-security` | JWT-secured endpoints |
| `spring-kafka` | Publish commands to downstream services |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for queries |
| `spring-boot-starter-data-redis` | Response caching |
| `jjwt-*` | JWT parsing & validation |
| `routify-common` | Shared DTOs and event types |

## REST API Overview

All endpoints require a valid Bearer JWT.

### Routes

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/routes` | List all routes (paged) |
| `GET` | `/api/routes/{id}` | Get route details |
| `POST` | `/api/routes` | Create a route |
| `PUT` | `/api/routes/{id}` | Update a route |
| `DELETE` | `/api/routes/{id}` | Delete a route |
| `POST` | `/api/routes/{id}/activate` | Activate a route |
| `POST` | `/api/routes/{id}/deactivate` | Deactivate a route |

### Filters

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/filters` | List filters |
| `POST` | `/api/filters` | Create a filter |
| `PUT` | `/api/filters/{id}` | Update a filter |
| `DELETE` | `/api/filters/{id}` | Delete a filter |

### Users & Tenants

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/users` | List users (paged) |
| `POST` | `/api/users` | Create a user |
| `GET` | `/api/tenants` | List tenants |
| `POST` | `/api/tenants` | Create a tenant |

### Audit

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/audit` | Query audit log (paged, filterable) |

### Certificates

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/certificates` | List certificates |
| `POST` | `/api/certificates` | Upload a certificate |
| `DELETE` | `/api/certificates/{id}` | Delete a certificate |

### Real-Time (SSE)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/events/routes` | SSE stream of route status changes |

## Messaging

### Kafka — Topics (published)

| Topic | Description |
|---|---|
| `routify.route.commands` | Route/filter CRUD commands |
| `routify.user.commands` | User CRUD commands |
| `routify.tenant.commands` | Tenant CRUD commands |
| `routify.cert.commands` | Certificate upload/delete commands |

### Kafka — Topics (consumed)

| Topic | Description |
|---|---|
| `routify.route.events` | Consumed to push real-time SSE updates to dashboard clients |

### RabbitMQ — Request/Reply (initiated)

Queries are sent to each downstream service and await a synchronous reply:

- Route & filter queries → `routify-route-service`
- User & tenant queries → `routify-identity-service`
- Audit queries → `routify-audit-service`
- Certificate queries → `routify-cert-vault`

## Building & Running

```bash
# Build
mvn clean package -pl routify-admin-api -am -DskipTests

# Run
java -jar target/routify-admin-api-2.0.0-SNAPSHOT.jar
```

### Required Infrastructure

- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose up -d` from the project root.

## Docker

```bash
docker build -t routify-admin-api .
docker run -p 8085:8085 routify-admin-api
```

