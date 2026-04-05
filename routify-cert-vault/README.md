# routify-cert-vault

Encrypted certificate storage vault for the Routify platform. Stores TLS certificates (PEM / PKCS12) AES-encrypted in PostgreSQL and exposes logical cert IDs for gateway mTLS configuration.

## Responsibilities

- **Certificate storage** — accepts PEM and PKCS12 certificate uploads; stores private key material **AES-encrypted** at rest
- **Cert groups** — logical groupings of certificates; referenced by `routify-api-gateway` for `AUTH_CERT_VAULT` / `CERT_VAULT_EXPIRY_CHECK` filter factories
- **Kafka command consumer** — all write operations arrive as `CommandEvent` records over Kafka from `routify-admin-api`
- **RabbitMQ query responder** — cert list, detail, and gateway-snapshot queries answered via RabbitMQ
- **Material provider** — serves decrypted PEM material to `routify-api-gateway` for in-memory `CertificateRegistry` loading (internal only — never called from admin-api)
- **No public REST API** — all operations are internal-only via messaging

## Module Info

| Property | Value |
|---|---|
| Artifact | `io.routify:routify-cert-vault` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8085` |
| Actuator port | `9085` |
| Java | 21 (Virtual Threads) |
| DB schema | `routify_cert` |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-jpa` | Encrypted certificate persistence |
| `flyway-core` | Schema migrations |
| `spring-kafka` | Consume certificate command events; publish lifecycle events |
| `spring-boot-starter-amqp` | RabbitMQ request/reply for cert queries |
| `commons-io` | PEM / PKCS12 parsing utilities |
| `routify-common` | Shared `CommandEvent`, `RabbitTopology` constants |

## Messaging

### Kafka — consumed (commands)

| Topic | Commands |
|---|---|
| `routify.cert.commands` | `UploadCertificate`, `RevokeCertificate`, `DeleteCertificate`, `MapCertificateToGateway`, `UnmapCertificateFromGateway`, `CreateCertGroup`, `UpdateCertGroup`, `ArchiveCertGroup`, `DeleteCertGroup`, `AddCertToGroup`, `RemoveCertFromGroup` |

### Kafka — published (events)

| Topic | Events |
|---|---|
| `routify.cert.events` | `CertificateUploaded`, `CertificateRevoked`, `CertificateDeleted`, `CertificateMapped` — consumed by `routify-audit-service` and `routify-api-gateway` |
| `routify.cert.group.events` | `CertGroupCreated`, `CertGroupUpdated`, `CertGroupArchived`, `CertGroupDeleted`, `CertAddedToGroup`, `CertRemovedFromGroup` — consumed by `routify-audit-service` and `routify-api-gateway` |

### RabbitMQ — request/reply (responded)

Exchange: `routify.cert-vault` (direct)

| Queue | Routing Key | Requester | Notes |
|---|---|---|---|
| `routify.cert-vault.certs.query` | `certs.query` | `routify-admin-api` | Paged cert list |
| `routify.cert-vault.certs.get` | `certs.get` | `routify-admin-api` | Single cert |
| `routify.cert-vault.certs.active` | `certs.active` | `routify-admin-api` | Active certs list |
| `routify.cert-vault.certs.stats` | `certs.stats` | `routify-admin-api` | Vault statistics |
| `routify.cert-vault.certs.gateway-snapshot` | `certs.gateway.snapshot` | `routify-admin-api` | Gateway-mapped certs |
| `routify.cert-vault.certs.fetch-material` | `certs.fetch-material` | `routify-api-gateway` | **Internal only** — decrypted PEM material for CertificateRegistry |
| `routify.cert-vault.cert-groups.query` | `cert-groups.query` | `routify-admin-api` | Paged cert-group list |
| `routify.cert-vault.cert-groups.get` | `cert-groups.get` | `routify-admin-api` | Single cert-group |
| `routify.cert-vault.cert-groups.members` | `cert-groups.members` | `routify-admin-api` | Cert-group members |

## Security

- Private key material is **AES-encrypted at rest** before persisting.
- Encryption key supplied via `CERT_VAULT_ENCRYPTION_KEY` environment variable — never hard-coded.
- `certs.fetch-material` is accessible only by the gateway over the internal RabbitMQ network.

## Database

- **Engine**: PostgreSQL 17
- **Migrations**: Flyway (`classpath:db/migration`), `ddl-auto: validate`
- **Schema**: `routify_cert`

## Building & Running

```bash
# Build
mvn clean package -pl routify-cert-vault -am -DskipTests

# Run
java -jar target/routify-cert-vault-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- PostgreSQL (`localhost:5432`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.

## Docker

```bash
docker build -t routify-cert-vault .
docker run -p 8085:8085 routify-cert-vault
```
