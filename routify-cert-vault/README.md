# routify-cert-vault

Secure certificate storage vault for the Routify platform. Allows operators to upload inbound TLS certificates (PEM / PKCS12) via the dashboard, stores them encrypted in PostgreSQL, and exposes logical certificate IDs that can be referenced in gateway TLS configuration for inbound mTLS termination.

## Responsibilities

- **Certificate storage** — accepts PEM and PKCS12 certificate uploads; stores private key material **AES-encrypted** in PostgreSQL
- **Logical cert IDs** — each certificate is assigned a UUID that can be referenced in route TLS configuration within `routify-api-gateway`
- **mTLS integration** — supplies certificates to `routify-api-gateway` for inbound mutual TLS termination
- **Kafka command consumer** — all write operations (upload, delete) arrive as Kafka commands from `routify-admin-api`
- **RabbitMQ query responder** — certificate list and detail queries from `routify-admin-api` are answered via RabbitMQ request/reply
- **No public REST API** — all operations are internal-only via messaging

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-cert-vault` |
| Version | `2.0.0-SNAPSHOT` |
| Default port | `8087` |
| Java | 21 (Virtual Threads) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | Actuator / health endpoints only |
| `spring-boot-starter-data-jpa` | Encrypted certificate persistence |
| `postgresql` | Database driver |
| `flyway-core` | Database migrations |
| `spring-kafka` | Consume certificate command events |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for certificate queries |
| `spring-boot-starter-security` | JWT-based inter-service security |
| `jjwt-*` | JWT validation |
| `spring-boot-starter-validation` | Bean Validation |
| `spring-boot-starter-actuator` | Health / readiness probes |
| `commons-io` | File/stream utilities for PEM/PKCS12 parsing |

## Messaging

### Kafka — Command Topics (consumed)

| Topic | Description |
|---|---|
| `routify.cert.commands` | `CertificateUpload`, `CertificateDelete` commands from `routify-admin-api` |

### Kafka — Event Topics (published)

| Topic | Description |
|---|---|
| `routify.cert.events` | `CertificateUploaded`, `CertificateDeleted` — consumed by `routify-audit-service` |

### RabbitMQ — Request/Reply (responded)

| Queue | Description |
|---|---|
| Certificate query queue | Certificate list and detail queries from `routify-admin-api` |

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`)
- **Schema**: `certificates` (stores encrypted key material, metadata, logical ID)

## Security

- Private key material is **AES-encrypted at rest** before being persisted.
- Encryption key is supplied via environment variable / Spring config — never hard-coded.
- Inter-service calls are authenticated via JWT (RS256).

## Building & Running

```bash
# Build
mvn clean package -pl routify-cert-vault -am -DskipTests

# Run
java -jar target/routify-cert-vault-2.0.0-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose up -d` from the project root.

## Docker

```bash
docker build -t routify-cert-vault .
docker run -p 8087:8087 routify-cert-vault
```

