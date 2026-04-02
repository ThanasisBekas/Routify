# routify-audit-service

Immutable audit log service for the Routify platform. Records every significant domain event and exposes query capabilities exclusively over messaging — no public REST endpoints are exposed.

## Responsibilities

- **Event ingestion** — consumes domain events from Kafka and persists immutable audit records to PostgreSQL
- **Audit queries** — answers paged and filtered audit log queries from `routify-admin-api` via RabbitMQ request/reply
- **Immutability guarantee** — audit records are never updated or deleted after creation

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-audit-service` |
| Version | `2.0.0-SNAPSHOT` |
| Default port | `8086` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | Actuator / health endpoints only |
| `spring-boot-starter-security` | Secures actuator endpoints |
| `spring-boot-starter-data-jpa` | Audit record persistence |
| `spring-boot-starter-validation` | Input validation |
| `postgresql` | Database driver |
| `flyway-core` | Database migrations |
| `spring-kafka` | Consume domain events from all services |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for audit queries |
| `routify-common` | Shared event types and DTOs |

## Messaging

### Kafka — Topics (consumed)

| Topic | Description |
|---|---|
| `routify.route.events` | Route lifecycle events |
| `routify.user.events` | User lifecycle events |
| `routify.tenant.events` | Tenant lifecycle events |
| `routify.cert.events` | Certificate lifecycle events |

### RabbitMQ — Request/Reply (responded)

| Queue | Description |
|---|---|
| Audit query queue | Paged, filterable audit log queries from `routify-admin-api` |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`)
- **Schema**: `audit_log` (append-only; no updates, no deletes)

## Building & Running

```bash
# Build
mvn clean package -pl routify-audit-service -am -DskipTests

# Run
java -jar target/routify-audit-service-2.0.0-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose up -d` from the project root.

## Docker

```bash
docker build -t routify-audit-service .
docker run -p 8086:8086 routify-audit-service
```

