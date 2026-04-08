# routify-audit-service — Agent Guide

Immutable audit log service. Consumes domain events, request telemetry, and AI decisions from Kafka; serves queries and replay capabilities via RabbitMQ RPC. **No public REST API.**

## Runtime

- **Port:** 8084 (actuator: 9084)
- **Runtime:** Spring Web with Virtual Threads
- **DB schema:** `routify_audit` — append-only; records are **never** updated or deleted

## Package Layout

```
io.routify.audit/
├── config/       # Kafka consumer config, RabbitMQ config, scheduling
├── consumer/     # Kafka consumers (one class per event category)
│   ├── DomainEventAuditConsumer       → route, filter, tenant, user, gateway, cert events
│   ├── RequestTelemetryConsumer       → gateway request/response records
│   ├── AiFilterDecisionConsumer       → AI filter verdicts
│   ├── AiModificationDecisionConsumer → AI modifier decisions
│   └── DlqEventConsumer              → all *.DLQ topics (failed events)
├── domain/       # AuditLogEntry, RequestLog, AiFilterDecision, AiModificationDecision,
│                 # AiPromptVersion, AlertRule, AlertEvent, DlqEvent, TenantUsageDaily
├── messaging/    # AuditRabbitHandler — all RabbitMQ RPC query handlers
├── replay/       # FailedRequestReplayService, ReplayScheduler
├── repository/   # Spring Data JPA repositories
└── scheduler/    # Scheduled jobs (usage aggregation, alert evaluation)
```

## Key Patterns

### Event ingestion
All consumers follow the same pattern:
1. `@KafkaListener(topics = KafkaTopics.*)` on consumer method
2. Deserialise event payload → create domain entity → persist via JPA
3. Audit records are append-only — no `UPDATE` or `DELETE` operations

### DLQ ingestion
`DlqEventConsumer` listens to `routify.*.DLQ` topics (pattern subscription). Persists failed event metadata for alerting and operational visibility.

### Query serving (RabbitMQ)
`AuditRabbitHandler` serves all queries from admin-api:
- `audit.events.query` → paged event log
- `audit.requests.query` → paged request log
- `audit.requests.stats` → per-route request statistics
- `audit.replay.single` / `audit.replay.bulk` → trigger request replay
- `audit.ai-filter.stats` / `audit.ai-filter.query` → AI filter analytics
- `audit.route.health` → per-route latency/error stats
- `audit.usage.current` / `audit.usage.history` → tenant usage metrics
- `audit.time-series` → time-bucketed analytics for GraphQL
- `alert-rules.query` / `alert-rules.command` → alert rule management

### Request replay
`FailedRequestReplayService` replays failed gateway requests by re-issuing them through the gateway. `ReplayScheduler` handles automatic retries for pending replays.

## Adding a new audit consumer

1. Create consumer class in `consumer/` with `@KafkaListener(topics = KafkaTopics.NEW_TOPIC)`
2. Create domain entity in `domain/` (append-only — no updates)
3. Add Flyway migration for new table in `src/main/resources/db/migration/`
4. Create repository in `repository/`

## Adding a new audit query

1. Add `QueryRequest`/`QueryResponse` records in `routify-common`
2. Add `RabbitTopology` queue/routing-key constants
3. Add `@RabbitListener` method in `AuditRabbitHandler`

## Build

```bash
mvn clean package -pl routify-audit-service -am -DskipTests
```

