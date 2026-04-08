# routify-identity-service — Agent Guide

Authentication and identity management service. Handles JWT issuance, user/tenant CRUD, API key lifecycle, roles, and webhooks.

## Runtime

- **Port:** 8083 (actuator: 9083)
- **Runtime:** Spring Web with Virtual Threads
- **DB schema:** `routify_identity` — tables: `users`, `tenants`, `roles`, `api_keys`, `webhook_subscriptions`, `webhook_deliveries`, `identity_outbox_events`, `processed_commands`

## Package Layout

```
io.routify.identity/
├── config/       # Kafka, RabbitMQ, scheduling config
├── controller/   # (minimal — auth/users proxied via messaging, not direct REST)
├── domain/       # AppUser, Tenant, RoleDefinition, ApiKey, WebhookSubscription, WebhookDelivery,
│                 # IdentityOutboxEvent, ProcessedCommand
├── dto/          # Request/response DTOs
├── messaging/    # Kafka consumers + RabbitMQ handler
│   ├── UserCommandKafkaConsumer      → CREATE_USER, UPDATE_USER, DELETE_USER
│   ├── AuthCommandKafkaConsumer      → LOGOUT (blacklists refresh token JTI)
│   ├── ApiKeyCommandConsumer         → CREATE_API_KEY, REVOKE_API_KEY, ROTATE_API_KEY
│   ├── WebhookCommandConsumer        → CREATE_WEBHOOK, UPDATE_WEBHOOK, DELETE_WEBHOOK
│   ├── WebhookEventConsumer          → Listens to domain events → dispatches webhook deliveries
│   └── IdentityRabbitHandler         → All RabbitMQ RPC queries (users, tenants, auth, api-keys, roles, webhooks)
├── outbox/       # IdentityOutboxPoller, IdentityOutboxEventStore (same pattern as route-service)
├── repository/   # Spring Data JPA repositories
├── security/     # JwtService (RS256 key pair, token generation/validation)
└── service/      # AuthService, UserService, TenantService, ApiKeyService, RoleService, WebhookService, WebhookDispatcher
```

## Key Patterns

### Auth flow
- Login: admin-api → RabbitMQ `auth.login` → `AuthService.login()` → returns JWT access + refresh tokens
- Refresh: admin-api → RabbitMQ `auth.refresh` → `AuthService.refresh()` → new access token
- Logout: admin-api → Kafka `AUTH_COMMANDS` → `AuthCommandKafkaConsumer` → blacklists refresh JTI in Redis
- JWT: RS256 keypair from `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` env vars (DER, base64-encoded)

### Command consumption
Same pattern as route-service — Kafka consumers switch on `CommandEvent` sealed subtypes. `ProcessedCommand` table ensures idempotency.

### Tenant commands — synchronous RabbitMQ
Unlike user commands (Kafka), tenant CRUD is handled synchronously via RabbitMQ RPC (`tenants.command` routing key) because tenant operations are rare admin actions that need immediate confirmation.

### Outbox
`IdentityOutboxPoller` — identical pattern to route-service. Publishes user/tenant domain events to `routify.user.events` and `routify.tenant.events` Kafka topics.

### Data seeder
`DataSeeder` runs on first boot — creates admin user. Set `ADMIN_INITIAL_PASSWORD` env var for a known password; otherwise a random one is printed to stdout once.

## Adding a new identity feature

1. Add Flyway migration in `src/main/resources/db/migration/`
2. Add/update JPA entity in `domain/`
3. Add service method in `service/`
4. For async writes: add `CommandEvent` record → Kafka consumer handler → outbox event
5. For sync RPC: add `QueryRequest`/`QueryResponse` records → `@RabbitListener` in `IdentityRabbitHandler`
6. Update `RabbitTopology` constants if new queues/routing keys are needed

## Build

```bash
mvn clean package -pl routify-identity-service -am -DskipTests
```

