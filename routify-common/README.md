# routify-common

Shared library module for the Routify platform. Provides the common building blocks consumed by every other Routify service.

## Responsibilities

- **Kafka messaging constants** — `KafkaTopics` (all topic names; never use string literals)
- **RabbitMQ topology constants** — `RabbitTopology` (exchanges, queues, routing keys, timeouts)
- **Sealed command events** — `CommandEvent` (Java 21 sealed interface; every Kafka write command is a nested `record`)
- **Domain events** — `DomainEvent` base type for Kafka event payloads
- **RabbitMQ request/reply types** — `QueryRequest` / `QueryResponse` typed wrappers for all RPC calls
- **Shared DTOs / types** — `PageResponse<T>`, `AsyncAcknowledgement` (HTTP 202 wrapper)
- **Exception hierarchy** — sealed `RoutifyException` (`NotFound`, `Conflict`, `Validation`, `BadRequest`, `Unauthorized`, `Forbidden`, `RateLimitExceeded`, `QuotaExceeded`, `GatewayError`, `HeuristicError`)
- **HTTP headers** — `RoutifyHeaders` (all `X-*` header names + `resolveActor` utility)
- **Security context** — `SecurityContext` record; `ThreadLocal`-backed; helpers: `hasRole()`, `isSuperAdmin()`, `isTenantAdmin()`
- **Redis keys** — `RedisKeys` (centralised key prefixes, e.g. `routify:token:blocklist:`)
- **Sensitive field masking** — `@SensitiveField` annotation + `Sensitive.maskFields()` / `Sensitive.isMasked()`
- **Kafka DLQ error handler** — `KafkaDlqErrorHandlerFactory` (exponential back-off, 1 s × 2.0, max 30 s ≈ 5 retries)
- **Metrics namespace** — `RoutifyMetrics` (all `routify.*` Micrometer metric names)
- **Domain enumerations** — `FilterType`, `TenantPlan`, `UserRole`, `RouteStatus`
- **Secret validation** — `SecretValidator` (fail-fast at startup via `routify.required-secrets`)

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-common` |
| Version | `1.0.2-SNAPSHOT` |
| Packaging | `jar` |
| Java | 21 |

## Package Structure

```
gr.routify.common/
├── client/         # AmqpServiceClientSupport, KafkaServiceClientSupport
├── config/         # SecretValidator
├── domain/         # FilterType, TenantPlan, UserRole, RouteStatus
├── dto/            # audit/ sub-packages
├── event/          # KafkaTopics, RabbitTopology, CommandEvent, DomainEvent,
│                   # QueryRequest, QueryResponse, AiFilterDecisionEvent, ...
├── exception/      # RoutifyException hierarchy, GlobalExceptionHandler
├── kafka/          # KafkaDlqErrorHandlerFactory
├── observability/  # RoutifyMetrics
├── security/       # SecurityContext, RedisKeys
└── web/            # RoutifyHeaders, AsyncAcknowledgement, PageResponse,
                    # Sensitive, SensitiveField
```

## Key Classes

| Class | Usage |
|---|---|
| `AmqpServiceClientSupport` | Extend for RabbitMQ RPC clients; call `rpc(routingKey, request, TypeRef)` or `send()` |
| `KafkaServiceClientSupport` | Extend for Kafka producers; `publishCommand()`, `publishEvent()`, `publish()`, `publishSync()` |
| `CommandEvent` | Deserialise with `objectMapper.readValue(json, CommandEvent.class)`; switch on sealed subtypes |
| `KafkaDlqErrorHandlerFactory` | `factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate))` |
| `SecurityContext` | `SecurityContext.current()` after JWT filter sets it; `SecurityContext.clear()` in finally |
| `Sensitive` | `Sensitive.maskFields(dto)` before returning to clients; `Sensitive.isMasked(v)` on incoming writes |

## Usage

```xml
<dependency>
    <groupId>gr.routify</groupId>
    <artifactId>routify-common</artifactId>
</dependency>
```

> Version is managed by the parent BOM (`routify-parent`).

## Building

```bash
mvn clean install -pl routify-common
```
