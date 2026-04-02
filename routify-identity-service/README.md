# routify-identity-service

Authentication and identity management service for the Routify platform.

## Responsibilities

- **JWT issuance** — issues, refreshes, and revokes access/refresh tokens (RS256) via `AuthController`
- **User management** — create, update, deactivate users; operations arrive as Kafka commands from `routify-admin-api`
- **Tenant management** — multi-tenant support; tenant CRUD via Kafka commands
- **Token blacklist** — revoked tokens tracked in Redis for instant invalidation
- **RabbitMQ responder** — answers user/tenant query requests (paged lists, single lookups) from `routify-admin-api`

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-identity-service` |
| Version | `2.0.0-SNAPSHOT` |
| Default port | `8081` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST (AuthController — login/refresh/logout) |
| `spring-boot-starter-security` | Security filter chain |
| `spring-boot-starter-data-jpa` | User/tenant persistence |
| `postgresql` | Database driver |
| `flyway-core` | Database migrations |
| `spring-boot-starter-data-redis` | Token blacklist store |
| `spring-kafka` | Consume user/tenant command events |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for queries |
| `jjwt-*` | JWT generation and validation |

## Public HTTP Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Authenticate and receive JWT tokens |
| `POST` | `/api/auth/refresh` | Refresh access token |
| `POST` | `/api/auth/logout` | Revoke tokens |

> All user and tenant management endpoints are **internal-only** and reachable exclusively via RabbitMQ / Kafka — no REST controllers are exposed for those operations.

## Messaging

### Kafka — Command Topics (consumed)

| Topic | Description |
|---|---|
| `routify.user.commands` | Create / update / deactivate users |
| `routify.tenant.commands` | Create / update / deactivate tenants |

### RabbitMQ — Request/Reply (responded)

| Queue | Description |
|---|---|
| User query queue | Paged user list, single user lookup |
| Tenant query queue | Paged tenant list, single tenant lookup |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`)
- **Schema**: `users`, `tenants`, `roles`

## Building & Running

```bash
# Build
mvn clean package -pl routify-identity-service -am -DskipTests

# Run
java -jar target/routify-identity-service-2.0.0-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose up -d` from the project root.

