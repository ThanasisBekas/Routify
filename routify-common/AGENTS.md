# routify-common — Agent Guide

Shared library consumed by every Routify service. **Not a Spring Boot application** — it is a plain JAR with no `main()` class.

## Purpose

Centralises all cross-cutting contracts so services stay in sync: event types, messaging constants, exception hierarchy, base clients, DTOs, and security context.

## Package Layout

```
io.routify.common/
├── client/         # AmqpServiceClientSupport, KafkaServiceClientSupport
├── config/         # SecretValidator (fail-fast on missing env vars)
├── crypto/         # @Sensitive, FieldEncryptionService, SensitiveStringConverter, FieldEncryptionAutoConfiguration
├── domain/         # FilterType, TenantPlan, UserRole, RouteStatus, RouteEnvironment
├── dto/            # Shared DTOs (audit sub-packages)
├── event/          # KafkaTopics, RabbitTopology, CommandEvent, DomainEvent,
│                   # QueryRequest, QueryResponse, RequestTelemetryEvent, AiFilterDecisionEvent
├── exception/      # RoutifyException (sealed), GlobalExceptionHandler
├── kafka/          # KafkaDlqErrorHandlerFactory
├── observability/  # RoutifyMetrics (all routify.* Micrometer names)
├── security/       # SecurityContext (ThreadLocal), RedisKeys
└── web/            # RoutifyHeaders, AsyncAcknowledgement, PageResponse
```

## Critical Rules

- **Never use string literals for topic/queue names.** Always reference `KafkaTopics.*` and `RabbitTopology.*` constants.
- **Never throw raw `RuntimeException`.** Use `RoutifyException.NotFound`, `.Conflict`, `.Validation`, `.BadRequest`, `.Unauthorized`, `.Forbidden`, `.RateLimitExceeded`, `.QuotaExceeded`, `.GatewayError`, or `.HeuristicError`.
- **Adding a new command:** Add a `record` to the `CommandEvent` sealed interface + `@JsonSubTypes.Type` annotation + `permits` clause. Every record must implement `commandId()`, `tenantId()`, `requestedBy()`, `issuedAt()`.
- **Adding a new query:** Add typed records to `QueryRequest` (sealed) and `QueryResponse` (sealed) with matching `@JsonSubTypes.Type`.
- **Adding a new RabbitMQ endpoint:** Add `EXCHANGE_*`, `QUEUE_*`, and `RK_*` constants to `RabbitTopology`.

## Key Classes

| Class | What it provides |
|---|---|
| `KafkaServiceClientSupport` | Base for Kafka producers — `publishCommand()`, `publishEvent()`, `publish()`, `publishSync()` |
| `AmqpServiceClientSupport` | Base for RabbitMQ RPC clients — `rpc()` (with 1 transparent retry), `send()` |
| `CommandEvent` | Sealed interface — Jackson `@JsonTypeInfo` discriminated union of all write commands |
| `DomainEvent` | Sealed interface — all Kafka domain event payloads |
| `QueryRequest` / `QueryResponse` | Sealed interfaces for typed RabbitMQ RPC request/reply |
| `GlobalExceptionHandler` | `@RestControllerAdvice` — maps `RoutifyException` → RFC 9457 ProblemDetail |
| `SecurityContext` | ThreadLocal holder — `SecurityContext.current()`, `hasRole()`, `isSuperAdmin()` |
| `KafkaDlqErrorHandlerFactory` | Creates Kafka error handler with exponential backoff → DLQ forwarding |
| `@Sensitive` / `FieldEncryptionService` | `@Sensitive` annotation for transparent AES-256-GCM field-level encryption at the JPA layer |
| `SensitiveStringConverter` | JPA `AttributeConverter` — encrypts on persist, decrypts on read; wired via `FieldEncryptionAutoConfiguration` |

## Build

```bash
mvn clean install -pl routify-common       # Install JAR to local repo
```

This module must be built before any other module — use `-am` flag when building dependents.

