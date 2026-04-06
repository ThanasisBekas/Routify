# Initiative 01 — API Key Lifecycle Management

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 1–3 · **Owner:** Identity + Admin teams

---

## Problem Statement

The `ApiKeyAuthGatewayFilterFactory` already authenticates requests using Redis Hash lookups (`RedisKeys.APIKEY_PREFIX`), but keys must be seeded manually via `redis-cli HSET`. There is no create/revoke/rotate UI, no audit trail, no expiry management, and no durable storage — if Redis flushes, all API keys are lost.

## Solution Overview

Introduce a full API key lifecycle managed by `routify-identity-service`, durably persisted in PostgreSQL (`routify_identity` schema), and projected to Redis for zero-latency gateway reads. The dashboard provides self-service key management; all mutations are audited.

```
Dashboard → admin-api ─── Kafka (APIKEY_COMMANDS) ──→ identity-service
                │                                           │
                │  RabbitMQ (APIKEYS_QUERY/GET)              │  Redis HSET/DEL
                ◄───────────────────────────────────────────┘  (projection)
                                                               │
                                                      api-gateway reads
                                                      (ApiKeyAuthGatewayFilterFactory)
```

---

## Detailed Implementation Steps

### Step 1: Domain Model (identity-service)

**Files to create/modify:**
- `routify-identity-service/src/main/resources/db/migration/V{next}__api_keys.sql`
- `routify-identity-service/src/main/java/io/routify/identity/domain/ApiKey.java`
- `routify-identity-service/src/main/java/io/routify/identity/repository/ApiKeyRepository.java`

**Schema:**
```sql
CREATE TABLE routify_identity.api_key (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    user_id         UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    key_hash        VARCHAR(512) NOT NULL UNIQUE,   -- SHA-256 of raw key
    key_prefix      VARCHAR(12) NOT NULL,            -- first 8 chars for display ("rtfy_a1b2...")
    role            VARCHAR(50) NOT NULL DEFAULT 'OPERATOR',
    email           VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | REVOKED | EXPIRED
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT fk_apikey_tenant FOREIGN KEY (tenant_id)
        REFERENCES routify_identity.tenant(id)
);
CREATE INDEX idx_apikey_tenant ON routify_identity.api_key(tenant_id, status);
```

**Entity:**
```java
@Entity @Table(name = "api_key", schema = "routify_identity")
public class ApiKey {
    @Id private UUID id;
    private UUID tenantId;
    private UUID userId;
    private String name;
    private String keyHash;      // SHA-256(rawKey)
    private String keyPrefix;    // "rtfy_a1b2..."
    @Enumerated(EnumType.STRING) private UserRole role;
    private String email;
    @Enumerated(EnumType.STRING) private ApiKeyStatus status;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private UUID createdBy;
    private Instant createdAt;
    private Instant revokedAt;
}
```

**Task list:**
- [x] Create Flyway migration
- [x] Create `ApiKey` entity + `ApiKeyStatus` enum (`ACTIVE`, `REVOKED`, `EXPIRED`)
- [x] Create `ApiKeyRepository` (Spring Data JPA)
- [x] Add `ApiKeyMapper` (MapStruct)

---

### Step 2: Kafka Command Types (routify-common)

**Files to modify:**
- `routify-common/.../event/CommandEvent.java` — add sealed record subtypes
- `routify-common/.../event/KafkaTopics.java` — add `APIKEY_COMMANDS` topic

**New command records:**
```java
// In CommandEvent sealed interface:
record CreateApiKey(UUID commandId, UUID tenantId, UUID userId,
    String name, UserRole role, String email, Instant expiresAt,
    String actor) implements CommandEvent {}

record RevokeApiKey(UUID commandId, UUID tenantId, UUID apiKeyId,
    String actor) implements CommandEvent {}

record RotateApiKey(UUID commandId, UUID tenantId, UUID apiKeyId,
    String actor) implements CommandEvent {}
```

**New topic:**
```java
public static final String APIKEY_COMMANDS = "routify.apikey.commands";
public static final String DLQ_APIKEY_COMMANDS = APIKEY_COMMANDS + ".DLQ";
```

**Task list:**
- [x] Add 3 command records to `CommandEvent`
- [x] Add topic constants to `KafkaTopics`
- [x] Add DLQ topic constant

---

### Step 3: Identity-Service Command Consumer

**Files to create:**
- `routify-identity-service/.../messaging/ApiKeyCommandConsumer.java`
- `routify-identity-service/.../service/ApiKeyService.java`

**Behavior:**
- On `CreateApiKey`: generate 40-char raw key (`rtfy_` + SecureRandom base62), hash with SHA-256, persist `ApiKey` entity, project to Redis Hash, publish `DomainEvent.ApiKeyCreated` to outbox.
- On `RevokeApiKey`: set `status=REVOKED`, delete Redis key, publish `DomainEvent.ApiKeyRevoked`.
- On `RotateApiKey`: revoke old key, create new key with same metadata, return new raw key.

**Redis projection (critical path):**
```java
// On create/rotate:
redisTemplate.opsForHash().putAll(
    RedisKeys.APIKEY_PREFIX + rawKey,
    Map.of("tenantId", tenantId, "userId", userId, "role", role, "email", email)
);
if (expiresAt != null) {
    redisTemplate.expireAt(RedisKeys.APIKEY_PREFIX + rawKey, expiresAt);
}

// On revoke:
redisTemplate.delete(RedisKeys.APIKEY_PREFIX + rawKey);
```

**Task list:**
- [x] Create `ApiKeyService` with create/revoke/rotate methods
- [x] Create `ApiKeyCommandConsumer` (Kafka listener)
- [x] Add Redis projection logic
- [x] Add outbox event publishing (reuse `IdentityOutboxPoller`)
- [x] Add DLQ error handler registration

---

### Step 4: RabbitMQ Query Handlers (identity-service)

**Files to modify:**
- `routify-common/.../event/RabbitTopology.java` — add queue/RK constants
- `routify-common/.../event/QueryRequest.java` — add query types
- `routify-common/.../event/QueryResponse.java` — add response types
- `routify-identity-service/.../messaging/` — add RabbitMQ handler

**New topology constants:**
```java
public static final String QUEUE_APIKEYS_QUERY = "routify.identity-service.apikeys.query";
public static final String RK_APIKEYS_QUERY    = "apikeys.query";
public static final String QUEUE_APIKEYS_GET   = "routify.identity-service.apikeys.get";
public static final String RK_APIKEYS_GET      = "apikeys.get";
```

**Task list:**
- [x] Add `RabbitTopology` constants
- [x] Add `QueryRequest.ApiKeysQuery` / `QueryRequest.ApiKeyGet` records
- [x] Add `QueryResponse.ApiKeyPage` / `QueryResponse.ApiKeyDetail` records
- [x] Implement `ApiKeyRabbitHandler` in identity-service

---

### Step 5: Admin-API Controller + Client

**Files to create:**
- `routify-admin-api/.../controller/AdminApiKeysController.java`
- `routify-admin-api/.../client/ApiKeyMessagingClient.java` (optional, or extend `IdentityMessagingClient`)

**Endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/api-keys` | Paginated list (RabbitMQ) |
| `GET` | `/api/v1/admin/api-keys/{id}` | Detail view (RabbitMQ) |
| `POST` | `/api/v1/admin/api-keys` | Create key → returns raw key once (Kafka) |
| `POST` | `/api/v1/admin/api-keys/{id}/revoke` | Revoke (Kafka) |
| `POST` | `/api/v1/admin/api-keys/{id}/rotate` | Rotate → returns new raw key (Kafka) |

Create and rotate are **synchronous RPC** (not async Kafka) because the raw key must be returned to the caller exactly once. Use `RabbitTopology` for these two operations.

**Task list:**
- [x] Create controller with 5 endpoints
- [x] Wire Resilience4j circuit breaker (`"identity-service"`)
- [x] Add `@PreAuthorize` (SUPER_ADMIN, TENANT_ADMIN only for create/revoke/rotate)

---

### Step 6: Dashboard Module

**Files to create:**
- `routify-dashboard/src/modules/api-keys/ApiKeysPage.tsx`
- `routify-dashboard/src/modules/api-keys/CreateApiKeyModal.tsx`
- `routify-dashboard/src/modules/api-keys/ApiKeyDetailModal.tsx`
- `routify-dashboard/src/api/apiKeysApi.ts`

**Key UX requirements:**
- Raw key shown in a copy-to-clipboard box only after creation — never displayed again.
- Key prefix (`rtfy_a1b2...`) shown in list for identification.
- "Rotate" action shows confirmation modal with the new key.
- Expiry countdown badge on keys approaching expiration.
- Status chips: `ACTIVE` (green), `REVOKED` (red), `EXPIRED` (gray).

**Task list:**
- [x] Create `apiKeysApi.ts` with typed functions
- [x] Add TypeScript types to `src/types/index.ts` (`ApiKeyDto`, `CreateApiKeyRequest`, etc.)
- [x] Create list page with DataTable
- [x] Create modal with copy-to-clipboard
- [x] Create detail/revoke/rotate modals
- [x] Add route in React Router config
- [x] Add sidebar navigation entry
- [x] Add MSW mock handlers in `src/mocks/handlers/`

---

### Step 7: Audit Integration

**Task list:**
- [x] Ensure outbox events for api-key create/revoke/rotate include `aggregateType: "API_KEY"` for audit-service consumption
- [x] Verify audit log entries appear in dashboard audit viewer

---

## Acceptance Criteria

- [x] Operator can create an API key from the dashboard and use it to authenticate a `curl` request through the gateway
- [x] Revoking a key immediately blocks gateway access (Redis projection is synchronous)
- [x] Rotating a key invalidates the old key and issues a new one atomically
- [x] Expired keys are automatically rejected by the gateway (Redis TTL)
- [x] All key lifecycle actions appear in the audit log
- [x] Raw key is shown only once at creation; only the prefix is stored/displayed afterward

