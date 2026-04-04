# routify-identity-service

Authentication and identity management service for the Routify platform.

## Responsibilities

- **JWT issuance** — issues, refreshes, and revokes RS256 access/refresh tokens
- **User management** — create, update, delete users; operations arrive as `CommandEvent` records over Kafka
- **Tenant management** — multi-tenant support; tenant CRUD; tenant-scoped operations are synchronous RabbitMQ RPC
- **Token blacklist** — revoked refresh tokens tracked in Redis for instant invalidation
- **Auth RPC responder** — handles login, refresh, and password-change requests from `routify-admin-api` via RabbitMQ

> The dashboard hits `/api/v1/auth/*` on `routify-admin-api`, which proxies everything here via RabbitMQ. The only direct HTTP access is via the admin-api JWT filter (public key for token validation).

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-identity-service` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8083` |
| Actuator port | `9083` |
| Java | 21 (Virtual Threads) |
| DB schema | `routify_identity` |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-jpa` | User/tenant persistence |
| `flyway-core` | Schema migrations |
| `spring-boot-starter-data-redis` | Token blacklist store |
| `spring-kafka` | Consume user/tenant command events; publish lifecycle events |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for auth + queries |
| `jjwt-*` | JWT generation and validation (RS256) |

## Messaging

### Kafka — consumed (commands)

| Topic | Commands |
|---|---|
| `routify.user.commands` | `CreateUser`, `UpdateUser`, `DeleteUser` |
| `routify.auth.commands` | `Logout` (blacklists refresh token JTI in Redis) |

> Tenant commands (`CreateTenant`, `UpdateTenant`, `SuspendTenant`, `ReactivateTenant`) are handled synchronously over RabbitMQ, not Kafka.

### Kafka — published (events)

| Topic | Events |
|---|---|
| `routify.user.events` | `UserCreated`, `UserUpdated`, `UserDeleted` |
| `routify.tenant.events` | `TenantCreated`, `TenantUpdated`, `TenantSuspended`, `TenantReactivated` |

### RabbitMQ — request/reply (responded)

Exchange: `routify.identity-service` (direct)

| Queue | Routing Key | Purpose |
|---|---|---|
| `routify.identity-service.auth.login` | `auth.login` | Login (issue tokens) |
| `routify.identity-service.auth.refresh` | `auth.refresh` | Refresh access token |
| `routify.identity-service.auth.change-password` | `auth.change-password` | Self password change |
| `routify.identity-service.users.change-password` | `users.change-password` | Admin password reset |
| `routify.identity-service.users.query` | `users.query` | Paged user list |
| `routify.identity-service.users.get` | `users.get` | Single user lookup |
| `routify.identity-service.tenants.query` | `tenants.query` | Paged tenant list |
| `routify.identity-service.tenants.get` | `tenants.get` | Single tenant lookup |
| `routify.identity-service.tenants.list-active` | `tenants.list-active` | Active workspaces (login dropdown) |
| `routify.identity-service.tenants.command` | `tenants.command` | Sync suspend / reactivate |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`), `ddl-auto: validate`
- **Schema**: `routify_identity` — tables: `users`, `tenants`, `roles`
- **Initial seed**: `DataSeeder` creates an admin user on first boot. Provide `ADMIN_INITIAL_PASSWORD` env var to set a known password; otherwise a random password is printed to stdout once.

## Building & Running

```bash
# Build
mvn clean package -pl routify-identity-service -am -DskipTests

# Run
java -jar target/routify-identity-service-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.
