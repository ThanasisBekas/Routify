# routify-common

Shared library module for the Routify platform. Provides the common building blocks consumed by every other Routify service.

## Responsibilities

- **Domain events** — strongly-typed Kafka event payloads (route changed, user created, audit event, etc.)
- **Shared DTOs / types** — `PageResponse<T>`, common request/response wrappers, enumerations
- **Exception hierarchy** — base exceptions and standardised error response model
- **Observability utilities** — logging helpers, correlation ID propagation
- **Security primitives** — JWT parsing utilities, shared security constants
- **Web utilities** — global exception handler, `ResponseEntity` helpers

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-common` |
| Version | `2.0.0-SNAPSHOT` |
| Packaging | `jar` |
| Java | 21 |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | `ResponseEntity`, MVC utilities |
| `spring-data-commons` | `Page<T>` / `Pageable` support |
| `spring-boot-starter-validation` | Bean Validation annotations |
| `spring-boot-starter-amqp` | RabbitMQ topology constants |
| `jackson-databind` + `jackson-datatype-jsr310` | JSON serialisation with Java-time support |
| `commons-lang3` | General utility helpers |
| `lombok` | Boilerplate reduction |

## Package Structure

```
gr.routify.common/
├── domain/         # Core domain value objects and enumerations
├── event/          # Kafka event payload records
├── exception/      # Exception hierarchy and error response DTOs
├── observability/  # Logging / tracing utilities
├── security/       # JWT helpers and security constants
└── web/            # Global exception handler, REST utilities
```

## Usage

Add as a Maven dependency in any sibling service:

```xml
<dependency>
    <groupId>gr.routify</groupId>
    <artifactId>routify-common</artifactId>
</dependency>
```

> The version is managed by the parent BOM (`routify-parent`).

## Building

```bash
mvn clean install -pl routify-common
```

