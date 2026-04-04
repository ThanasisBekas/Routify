# routify-audit-service

Immutable audit log service for the Routify platform. Records every significant domain event and exposes query and replay capabilities exclusively over messaging — no public REST endpoints.

## Responsibilities

- **Event ingestion** — consumes domain events from multiple Kafka topics and persists immutable audit records
- **Request telemetry ingestion** — consumes `REQUEST_TELEMETRY` events from the gateway for request-level analytics
- **AI decision ingestion** — consumes `AI_FILTER_DECISIONS` and `AI_MODIFICATION_EVENTS` from `routify-ai-service`
- **DLQ ingestion** — consumes all `*.DLQ` topics and persists failed-event records for alerting and replay
- **Audit queries** — answers paged/filtered event log and request log queries from `routify-admin-api` via RabbitMQ
- **Request replay** — `FailedRequestReplayService` / `ReplayScheduler` manage replay lifecycle; served via RabbitMQ
- **Immutability guarantee** — audit records are never updated or deleted after creation

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-audit-service` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8084` |
| Actuator port | `9084` |
| Java | 21 (Virtual Threads) |
| DB schema | `routify_audit` |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-jpa` | Audit record persistence |
| `flyway-core` | Schema migrations |
| `spring-kafka` | Consume domain, telemetry, AI, and DLQ events |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for audit queries and replay |
| `routify-common` | Shared event types and DTOs |

## Messaging

### Kafka — consumed

| Topic | Consumer class | Purpose |
|---|---|---|
| `routify.route.events` | `DomainEventAuditConsumer` | Persist route lifecycle audit entries |
| `routify.filter.events` | `DomainEventAuditConsumer` | Persist filter lifecycle audit entries |
| `routify.tenant.events` | `DomainEventAuditConsumer` | Persist tenant lifecycle audit entries |
| `routify.user.events` | `DomainEventAuditConsumer` | Persist user lifecycle audit entries |
| `routify.gateway.reload` | `DomainEventAuditConsumer` | Persist gateway reload audit entries |
| `routify.cert.events` | `DomainEventAuditConsumer` | Persist certificate lifecycle audit entries |
| `routify.cert.group.events` | `DomainEventAuditConsumer` | Persist cert-group lifecycle audit entries |
| `routify.request.telemetry` | `RequestTelemetryConsumer` | Persist gateway request/response records |
| `routify.ai.filter.decisions` | `AiFilterDecisionConsumer` | Persist AI filter verdicts |
| `routify.ai.modification.events` | `AiModificationDecisionConsumer` | Persist AI modifier decisions |
| `routify.*.DLQ` (all DLQ topics) | `DlqEventConsumer` | Persist failed events for alerting and replay |

### RabbitMQ — request/reply (responded)

Exchange: `routify.audit-service` (direct)

| Queue | Routing Key | Purpose |
|---|---|---|
| `routify.audit-service.events.query` | `audit.events.query` | Paged event log queries |
| `routify.audit-service.requests.query` | `audit.requests.query` | Paged request log queries |
| `routify.audit-service.requests.stats` | `audit.requests.stats` | Per-route request stats |
| `routify.audit-service.replay.failed.query` | `audit.replay.failed.query` | Failed replay list |
| `routify.audit-service.replay.pending.query` | `audit.replay.pending.query` | Pending replay list |
| `routify.audit-service.replay.stats` | `audit.replay.stats` | Replay stats |
| `routify.audit-service.replay.single` | `audit.replay.single` | Trigger single request replay |
| `routify.audit-service.replay.bulk` | `audit.replay.bulk` | Trigger bulk replay |
| `routify.audit-service.ai-filter.stats` | `audit.ai-filter.stats` | AI filter decision analytics |
| `routify.audit-service.ai-filter.query` | `audit.ai-filter.query` | Paged AI filter decision log |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`), `ddl-auto: validate`
- **Schema**: `routify_audit` — append-only; records are never updated or deleted

## Building & Running

```bash
# Build
mvn clean package -pl routify-audit-service -am -DskipTests

# Run
java -jar target/routify-audit-service-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.

## Docker

```bash
docker build -t routify-audit-service .
docker run -p 8084:8084 routify-audit-service
```
