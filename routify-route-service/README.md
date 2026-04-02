# routify-route-service

Internal service responsible for persisting and managing API route and filter definitions within the Routify platform.

## Responsibilities

- **Route CRUD** — create, update, activate, deactivate, and delete route definitions
- **Filter management** — associate pre/post filters with individual routes
- **Outbox pattern** — publishes domain events to Kafka using the Transactional Outbox pattern to guarantee at-least-once delivery
- **Route snapshot** — exposes a cached snapshot of all active routes via RabbitMQ for `routify-api-gateway` on startup
- **Zero-downtime activation** — activating or modifying a route emits a Kafka event that triggers immediate hot-reload in `routify-api-gateway`

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-route-service` |
| Version | `2.0.0-SNAPSHOT` |
| Default port | `8082` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | Internal HTTP (health, actuator only) |
| `spring-boot-starter-data-jpa` | Route/filter persistence |
| `postgresql` | Database driver |
| `flyway-core` | Database migrations |
| `spring-boot-starter-data-redis` | Route snapshot cache |
| `spring-kafka` | Kafka Outbox event publishing & command consumption |
| `spring-boot-starter-amqp` | RabbitMQ request/reply — snapshot + status queries |
| `resilience4j` | Circuit breaker / retry for downstream resilience |

## Messaging

### Kafka — Command Topics (consumed)

| Topic | Description |
|---|---|
| `routify.route.commands` | Create / update / activate / deactivate / delete routes |
| `routify.filter.commands` | Create / update / delete filters on routes |

### Kafka — Event Topics (published)

| Topic | Description |
|---|---|
| `routify.route.events` | `RouteCreated`, `RouteUpdated`, `RouteActivated`, `RouteDeactivated`, `RouteDeleted` |

### RabbitMQ — Request/Reply (responded)

| Queue | Description |
|---|---|
| Route snapshot queue | Full active-route snapshot consumed by `routify-api-gateway` at startup |
| Route query queue | Paged route list and single route lookup for `routify-admin-api` |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`)
- **Schema**: `routes`, `filters`, `outbox_events`

## Building & Running

```bash
# Build
mvn clean package -pl routify-route-service -am -DskipTests

# Run
java -jar target/routify-route-service-2.0.0-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose up -d` from the project root.

