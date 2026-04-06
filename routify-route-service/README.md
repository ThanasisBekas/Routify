# routify-route-service

Internal service responsible for persisting API route and filter definitions, publishing domain events via the Transactional Outbox pattern, and serving gateway snapshots.

## Responsibilities

- **Route CRUD** — create, update, activate, deactivate, and delete route definitions
- **Filter management** — create, update, delete, attach/detach filters on routes
- **Transactional Outbox** — guarantees at-least-once Kafka event delivery (`OutboxPoller` polls every 250 ms, retries failed after 30 s, max 5 attempts)
- **Route snapshot** — serves a cached snapshot of all active routes to `routify-api-gateway` at startup via RabbitMQ
- **Gateway config** — persists and serves CORS / security-headers / rate-limit / circuit-breaker config via RabbitMQ

> No public REST API — all write operations arrive as `CommandEvent` records over Kafka from `routify-admin-api`.

## Module Info

| Property | Value |
|---|---|
| Artifact | `io.routify:routify-route-service` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8081` |
| Actuator port | `9081` |
| Java | 21 (Virtual Threads) |
| DB schema | `routify` |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-jpa` | Route/filter/outbox persistence |
| `flyway-core` | Schema migrations (`classpath:db/migration`) |
| `spring-boot-starter-data-redis` | Active-route snapshot cache |
| `spring-kafka` | Outbox event publishing; command consumption |
| `spring-boot-starter-amqp` | RabbitMQ request/reply — snapshot + query responder |

## Messaging

### Kafka — consumed (commands)

| Topic | Commands |
|---|---|
| `routify.route.commands` | `CreateRoute`, `UpdateRoute`, `ActivateRoute`, `DeactivateRoute`, `DeleteRoute` |
| `routify.filter.commands` | `CreateFilter`, `UpdateFilter`, `DeleteFilter`, `AttachFilter`, `DetachFilter` |

### Kafka — published (events, via Outbox)

| Topic | Events |
|---|---|
| `routify.route.events` | `RouteCreated`, `RouteUpdated`, `RouteActivated`, `RouteDeactivated`, `RouteDeleted` |
| `routify.filter.events` | `FilterCreated`, `FilterUpdated`, `FilterDeleted`, `FilterAttached`, `FilterDetached` |
| `routify.gateway.reload` | Published when a filter type requires a full gateway reload |
| `routify.gateway.config` | Published when gateway-wide config is saved |

### RabbitMQ — request/reply (responded)

Exchange: `routify.route-service` (direct)

| Queue | Routing Key | Requester |
|---|---|---|
| `routify.route-service.gateway-snapshot` | `route.gateway.snapshot` | `routify-api-gateway` (startup) |
| `routify.route-service.routes.query` | `routes.query` | `routify-admin-api` |
| `routify.route-service.routes.get` | `routes.get` | `routify-admin-api` |
| `routify.route-service.routes.clone` | `routes.clone` | `routify-admin-api` |
| `routify.route-service.filters.query` | `filters.query` | `routify-admin-api` |
| `routify.route-service.filters.get` | `filters.get` | `routify-admin-api` |
| `routify.route-service.route-stats` | `route.stats` | `routify-admin-api` |
| `routify.route-service.gateway-config.get` | `gateway.config.get` | `routify-admin-api` |
| `routify.route-service.gateway-config.save` | `gateway.config.save` | `routify-admin-api` |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`), `ddl-auto: validate`
- **Schema**: `routify` — tables: `routes`, `filters`, `outbox_events`

## Building & Running

```bash
# Build
mvn clean package -pl routify-route-service -am -DskipTests

# Run
java -jar target/routify-route-service-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.
