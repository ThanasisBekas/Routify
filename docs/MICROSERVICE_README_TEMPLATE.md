# routify-{service-name}

<!-- Replace {service-name} and all placeholders marked with {PLACEHOLDER} -->

> **{One-line description of what this service does within the Routify platform.}**

Part of the [Routify](../README.md) API Gateway Platform.

---

## 📖 Purpose

{Detailed description of this service's responsibilities. Explain:
- What domain this service owns
- What data it manages
- How it fits into the broader Routify architecture
- Key design decisions (e.g., "stateless — no database", "only service with JWT private key")}

## 🏗 Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 25 | Language (sealed types, records, pattern matching, virtual threads) |
| Spring Boot | 4.0.5 | Application framework |
| {Framework} | {version} | {purpose — e.g., Spring Cloud Gateway, Spring AI, Spring Data JPA} |
| PostgreSQL | 17 | Persistent storage (schema: `{schema_name}`) |
| Apache Kafka | 3.9 | Async commands & domain event publishing |
| RabbitMQ | 3.13 | Synchronous RPC (Direct Reply-To pattern) |
| Redis | 7 | {purpose — caching, rate limiting, session state, verdict caching} |
| Flyway | — | Database schema migrations |
| MapStruct | 1.6.3 | Object mapping (DTO ↔ entity) |
| Lombok | 1.18.44 | Boilerplate reduction |

<!-- Remove rows that don't apply (e.g., remove PostgreSQL/Flyway for stateless services) -->

## 📡 Ports

| Port | Purpose |
|---|---|
| `{APP_PORT}` | Application (HTTP) |
| `{MGMT_PORT}` | Management / Actuator (health probes, metrics, Prometheus) |

Health endpoints:
- Liveness: `http://localhost:{MGMT_PORT}/actuator/health/liveness`
- Readiness: `http://localhost:{MGMT_PORT}/actuator/health/readiness`

## 🔧 Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_HOST` | Yes | `localhost` | PostgreSQL host |
| `DB_NAME` | No | `routify` | PostgreSQL database name |
| `DB_USER` | No | `routify` | PostgreSQL username |
| `DB_PASS` | **Yes** | — | PostgreSQL password |
| `KAFKA_BOOTSTRAP` | Yes | `localhost:9092` | Kafka bootstrap servers |
| `RABBITMQ_HOST` | Yes | `localhost` | RabbitMQ host |
| `RABBITMQ_PASS` | **Yes** | — | RabbitMQ password |
| `REDIS_HOST` | Yes | `localhost` | Redis host |
| `REDIS_PASS` | **Yes** | — | Redis password |
| `JWT_PUBLIC_KEY` | Yes | — | RSA-2048 public key (SPKI, DER, base64) for JWT verification |
| `FIELD_ENCRYPTION_KEY` | **Yes** | — | AES-256 key for `@Sensitive` field encryption at rest |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No | `http://localhost:4318/v1/traces` | OpenTelemetry OTLP exporter endpoint |

<!-- Add/remove rows as needed. Mark variables without defaults as **Yes** under Required. -->

> **Startup validation:** This service declares `routify.required-secrets` in `application.yml`. The `SecretValidator` will fail startup if any required secret is blank. See `application.yml` for the exact list.

## 🚀 Local Development

### Prerequisites

- Java 25+
- Maven 3.9+
- Running infrastructure: PostgreSQL, Kafka, RabbitMQ, Redis (via `docker compose up -d` from project root)
- Environment file: `.env` at project root (generated via the "Generate .env" GitHub Actions workflow or copied from `.env.example`)

### Build

```bash
# From the project root (builds all modules including routify-common)
mvn clean package -DskipTests

# Or build just this module (requires routify-common to be installed first)
mvn clean package -DskipTests -pl routify-{service-name} -am
```

### Run

**Option A: IntelliJ IDEA**

Use the pre-configured `.run/routify-{service-name}.run.xml` — it automatically loads the root `.env` file.

**Option B: Maven**

```bash
cd routify-{service-name}
mvn spring-boot:run
```

> Note: You'll need to export the required environment variables or create a local `.env` file first.

**Option C: Docker**

```bash
# From project root
docker compose -f docker-compose.yml -f docker-compose.app.yml up {docker-service-name} -d
```

### Database Migrations

This service uses Flyway with Hibernate `validate` mode. Migrations are located in:

```
src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__*.sql
└── ...
```

- **Never** use Hibernate `create` or `update` DDL mode
- Schema changes must be a new Flyway migration file: `V<next_number>__<description>.sql`
- All migrations target the `{schema_name}` schema

## 📬 API / Events

### Kafka Topics

#### Published (Domain Events via Outbox)

| Topic | Event Types | Description |
|---|---|---|
| `{topic}` | `{EventType1}`, `{EventType2}` | {description} |

<!-- Example:
| `routify.route.events` | `RouteCreated`, `RouteUpdated`, `RouteDeleted` | Route lifecycle events consumed by gateway and audit-service |
-->

#### Consumed (Commands)

| Topic | Command Types | Description |
|---|---|---|
| `{topic}` | `{CommandType1}`, `{CommandType2}` | {description} |

<!-- Example:
| `routify.route.commands` | `CreateRoute`, `UpdateRoute`, `DeleteRoute` | Commands from admin-api triggered by dashboard actions |
-->

### RabbitMQ Queues (Sync RPC)

| Exchange | Queue | Routing Key | Description |
|---|---|---|---|
| `{exchange}` | `{queue}` | `{routing-key}` | {description} |

<!-- Example:
| `routify.route-service` | `routify.route-service.gateway-snapshot` | `route.gateway.snapshot` | Serves full gateway route snapshot to api-gateway at startup |
| `routify.route-service` | `routify.route-service.routes.query` | `routes.query` | Serves paginated route list queries from admin-api |
-->

> All Kafka topic constants are defined in [`routify-common/.../event/KafkaTopics.java`](../routify-common/src/main/java/io/routify/common/event/KafkaTopics.java).
> All RabbitMQ topology constants are defined in [`routify-common/.../event/RabbitTopology.java`](../routify-common/src/main/java/io/routify/common/event/RabbitTopology.java).

### REST Endpoints

<!-- If this service exposes REST endpoints (e.g., identity-service auth endpoints, cert-vault direct upload) -->

| Method | Path | Description |
|---|---|---|
| `{METHOD}` | `{path}` | {description} |

<!-- If this service has no direct REST endpoints, replace with:
> This service has no externally-exposed REST endpoints. All communication is via Kafka commands and RabbitMQ RPC.
-->

## 🧪 Testing

### Unit Tests

```bash
mvn test -pl routify-{service-name}
```

Test files follow the `*Test.java` naming convention and are executed by the Maven Surefire plugin.

### Integration Tests

```bash
mvn verify -pl routify-{service-name} -DskipITs=false
```

Integration tests (`*IT.java`) use Testcontainers with a shared base class (`{ServiceName}IntegrationBase.java`) that starts PostgreSQL + Kafka + RabbitMQ containers via the singleton pattern.

> Integration tests are disabled by default due to Docker Engine 29.x / Testcontainers compatibility. Re-enable with `-DskipITs=false`.

## 📁 Project Structure

```
routify-{service-name}/
├── src/
│   ├── main/
│   │   ├── java/io/routify/{package}/
│   │   │   ├── {ServiceName}Application.java     # Spring Boot entry point
│   │   │   ├── config/                            # Spring configuration classes
│   │   │   ├── domain/                            # JPA entities
│   │   │   ├── dto/                               # Request/response DTOs (records)
│   │   │   ├── mapper/                            # MapStruct mappers
│   │   │   ├── repository/                        # Spring Data JPA repositories
│   │   │   ├── service/                           # Business logic
│   │   │   ├── messaging/                         # Kafka consumers & RabbitMQ listeners
│   │   │   └── outbox/                            # Transactional outbox (if applicable)
│   │   └── resources/
│   │       ├── application.yml                    # Service configuration
│   │       └── db/migration/                      # Flyway SQL migrations
│   └── test/
│       ├── java/io/routify/{package}/
│       │   ├── *Test.java                         # Unit tests
│       │   ├── *IT.java                           # Integration tests
│       │   └── {ServiceName}IntegrationBase.java  # Testcontainers base class
│       └── resources/
│           └── application-test.yml               # Test profile overrides
├── Dockerfile                                     # Multi-stage build (Maven + JRE)
├── Dockerfile.ci                                  # CI build (pre-built JAR copy)
└── pom.xml                                        # Module POM
```

<!-- Adjust the tree above to match the actual structure of this service -->

## 🔗 Dependencies

### Depends On

- **routify-common** — shared library (events, DTOs, encryption, topology constants)
- {Other service dependencies at startup, e.g., "PostgreSQL — for schema migrations via Flyway"}

### Depended On By

- {Services that consume this service's events or query it via RabbitMQ}

<!-- Example:
- **routify-api-gateway** — consumes route events via Kafka for hot-reload
- **routify-admin-api** — queries routes via RabbitMQ RPC
- **routify-audit-service** — consumes route events for audit logging
-->

---

*For the full platform documentation, see the [root README](../README.md).*

