# routify-cert-vault — Agent Guide

Encrypted certificate storage vault. Stores TLS certificates (PEM/PKCS12) with **AES-encrypted** private key material at rest. **No public REST API** — all operations via Kafka commands + RabbitMQ RPC.

## Runtime

- **Port:** 8085 (actuator: 9085)
- **Runtime:** Spring Web with Virtual Threads
- **DB schema:** `routify_cert` — tables: `stored_certificates`, `cert_groups`, `cert_group_members`, `cert_outbox_events`, `processed_commands`, `acme_accounts`, `acme_orders`

## Package Layout

```
io.routify.cert/
├── domain/       # StoredCertificate, CertGroup, CertOutboxEvent, ProcessedCommand, AcmeAccount, AcmeOrder
├── exception/    # CertVaultEncryptionException
├── messaging/    # CertCommandKafkaConsumer, CertVaultRabbitHandler
├── repository/   # Spring Data JPA repositories (StoredCertificateRepository, CertGroupRepository, etc.)
└── service/      # CertificateVaultService, CertGroupService, CertEncryptionService, AcmeChallengeStore
```

## Key Patterns

### Encryption
`CertEncryptionService` handles AES encryption/decryption of private key material. The encryption key is supplied via `CERT_VAULT_ENCRYPTION_KEY` env var (AES-256, 32 bytes, base64-encoded). **Never hardcode or log this key.**

### Command consumption (Kafka)
`CertCommandKafkaConsumer` switches on `CommandEvent` sealed subtypes:
- `UploadCertificate`, `RevokeCertificate`, `DeleteCertificate`
- `MapCertificateToGateway`, `UnmapCertificateFromGateway`
- `CreateCertGroup`, `UpdateCertGroup`, `ArchiveCertGroup`, `DeleteCertGroup`
- `AddCertToGroup`, `RemoveCertFromGroup`

Idempotency via `ProcessedCommand` table (same pattern as route-service).

### Outbox
`CertOutboxEvent` + outbox poller publishes to `routify.cert.events` and `routify.cert.group.events` Kafka topics. Consumed by gateway (certificate hot-reload) and audit-service.

### Query serving (RabbitMQ)
`CertVaultRabbitHandler` serves:
- `certs.query` / `certs.get` / `certs.active` / `certs.stats` → admin-api
- `certs.gateway.snapshot` → admin-api (gateway TLS overview)
- `certs.fetch-material` → **internal only** — decrypted PEM material for gateway's `CertificateRegistry`. Never expose outside the service mesh.
- `cert-groups.query` / `cert-groups.get` / `cert-groups.members` → admin-api
- `acme.register` / `acme.issue` / `acme.renew` / `acme.orders.query` / `acme.orders.get` → ACME certificate management

### Security boundary
The `certs.fetch-material` RPC endpoint returns **decrypted** private key material. It is only called by `routify-api-gateway` over the internal RabbitMQ network. Admin-api never calls this endpoint.

## Build

```bash
mvn clean package -pl routify-cert-vault -am -DskipTests
```

