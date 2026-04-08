# routify-route-service — Agent Guide

Internal service that persists route and filter definitions and publishes domain events via the Transactional Outbox pattern. **No public REST API** — all writes arrive as Kafka commands, all reads are served via RabbitMQ RPC.

## Runtime

- **Port:** 8081 (actuator: 9081)
- **Runtime:** Spring Web with Virtual Threads
- **DB schema:** `routify` — tables: `routes`, `filters`, `route_filters`, `outbox_events`, `processed_commands`, `gateway_config`, `route_slo`

## Package Layout

```
io.routify.route/
├── config/       # Kafka, RabbitMQ, cache, scheduling config
├── domain/       # Route, FilterDefinition, RouteFilter, OutboxEvent, ProcessedCommand, GatewayConfig, RouteSlo
├── dto/          # RouteDto, FilterDefinitionDto (nested records: CreateRequest, Response, Summary, GatewaySnapshot)
├── mapper/       # RouteMapper (MapStruct) — entity↔DTO conversions
├── messaging/    # RouteCommandKafkaConsumer, RouteServiceRabbitHandler, TenantEventKafkaConsumer
├── outbox/       # OutboxPoller, OutboxEventStore, OutboxNotifyListener
├── repository/   # Spring Data JPA repositories
└── service/      # RouteService, FilterDefinitionService, GatewayConfigService, TenantPlanCache
```

## Key Patterns

### Command consumption (Kafka)
`RouteCommandKafkaConsumer` deserialises `CommandEvent` → `switch` on sealed subtypes:
```java
case CommandEvent.CreateRoute c  -> routeService.create(c, c.tenantId(), c.requestedBy());
case CommandEvent.UpdateRoute c  -> routeService.update(c.id(), c.tenantId(), c);
```
Commands are idempotent — `ProcessedCommand` table tracks `commandId` to prevent re-execution.

### Transactional Outbox
1. Service method writes entity + inserts `OutboxEvent` in same transaction via `OutboxEventStore`
2. `OutboxPoller` (scheduled every 250ms) reads PENDING events, deserialises JSON → typed `DomainEvent`, publishes to Kafka with confirmed ACK
3. On success → `markPublished()`; on failure → `markFailed()` (retried after 30s, max 5 attempts)
4. Graceful shutdown: `@PreDestroy` waits for in-progress batch to finish

### Query serving (RabbitMQ)
`RouteServiceRabbitHandler` has `@RabbitListener` methods for each queue:
- `routes.query` → paged route list
- `routes.get` → single route
- `routes.clone` → deep-clone route with filters
- `filters.query` / `filters.get`
- `gateway.config.get` / `gateway.config.save`
- `route.gateway.snapshot` → full active routes snapshot for gateway startup
- `route.stats` → route/filter counts
- `route-slo.get` / `route-slo.save`

### MapStruct mapper
`RouteMapper` (`@Mapper(componentModel = "spring")`) converts:
- `Route` → `RouteDto.Response` / `RouteDto.Summary` / `RouteDto.GatewaySnapshot`
- `FilterDefinition` → `FilterDefinitionDto.Response` / `.Summary`
- `RouteDto.CreateRequest` → `Route`

Custom `@Named` methods handle nested collections (filter refs, gateway config refs).

## Adding a new route/filter feature

1. Add Flyway migration in `src/main/resources/db/migration/`
2. Update JPA entity in `domain/`
3. Update service method in `service/`
4. Add/update `CommandEvent` record in `routify-common` if it's a new command
5. Handle the new case in `RouteCommandKafkaConsumer`'s switch
6. Publish domain event via `OutboxEventStore.save(topic, event)`
7. Update MapStruct mapper if DTOs change

## Build

```bash
mvn clean package -pl routify-route-service -am -DskipTests
```

