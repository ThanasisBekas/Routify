# Initiative 03 — Webhook Notification System

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 3–5 · **Owner:** Identity-service + Admin-API teams

---

## Problem Statement

External systems (Slack, PagerDuty, custom dashboards, CI pipelines) that need to react to Routify platform events must poll the admin-api or maintain a persistent WebSocket/SSE connection. This is fragile, doesn't work across firewalls, and requires custom integration code for each consumer.

## Solution Overview

Tenant-scoped webhook subscriptions that receive authenticated HTTP POST callbacks when matching platform events occur. Subscriptions are managed via the dashboard and admin-api. Delivery is reliable (3 retries with exponential backoff), verifiable (HMAC signature), and auditable (delivery log with status).

---

## Detailed Implementation Steps

### Step 1: Domain Model (identity-service)

**Files to create:**
- `routify-identity-service/src/main/resources/db/migration/V{next}__webhook_subscriptions.sql`
- `routify-identity-service/.../domain/WebhookSubscription.java`
- `routify-identity-service/.../domain/WebhookDelivery.java`
- `routify-identity-service/.../repository/WebhookSubscriptionRepository.java`
- `routify-identity-service/.../repository/WebhookDeliveryRepository.java`

**Schema:**
```sql
CREATE TABLE routify_identity.webhook_subscription (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    url             VARCHAR(2048) NOT NULL,
    secret          VARCHAR(255) NOT NULL,  -- HMAC-SHA256 signing key
    event_types     TEXT[] NOT NULL,         -- e.g., {'ROUTE_ACTIVATED','CERT_EXPIRING'}
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failure_count   INT NOT NULL DEFAULT 0,
    last_delivered_at TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE routify_identity.webhook_delivery (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID NOT NULL REFERENCES routify_identity.webhook_subscription(id),
    event_type          VARCHAR(100) NOT NULL,
    payload             JSONB NOT NULL,
    response_status     INT,
    response_body       TEXT,
    attempt             INT NOT NULL DEFAULT 1,
    status              VARCHAR(20) NOT NULL,  -- PENDING, DELIVERED, FAILED
    delivered_at        TIMESTAMPTZ,
    next_retry_at       TIMESTAMPTZ,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_sub_tenant ON routify_identity.webhook_subscription(tenant_id, status);
CREATE INDEX idx_webhook_delivery_sub ON routify_identity.webhook_delivery(subscription_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_retry ON routify_identity.webhook_delivery(status, next_retry_at)
    WHERE status = 'PENDING';
```

**Task list:**
- [x] Create Flyway migration
- [x] Create `WebhookSubscription` entity
- [x] Create `WebhookDelivery` entity
- [x] Create repository interfaces
- [x] Create `WebhookEventType` enum in `routify-common`

---

### Step 2: Webhook Event Type Catalog (routify-common)

**File to create:**
- `routify-common/.../domain/WebhookEventType.java`

**Values:**
```java
public enum WebhookEventType {
    ROUTE_CREATED, ROUTE_ACTIVATED, ROUTE_DEACTIVATED, ROUTE_DELETED, ROUTE_PROMOTED,
    FILTER_CREATED, FILTER_UPDATED, FILTER_DELETED,
    CERT_UPLOADED, CERT_REVOKED, CERT_EXPIRING, CERT_EXPIRED,
    USER_CREATED, USER_DELETED,
    TENANT_SUSPENDED, TENANT_REACTIVATED,
    AI_FILTER_BLOCKED, AI_FILTER_FLAGGED,
    DLQ_OVERFLOW,         // >N DLQ events in window
    GATEWAY_RELOAD_FAILED
}
```

**Task list:**
- [x] Create enum
- [x] Add TypeScript mirror type to `src/types/index.ts`

---

### Step 3: Webhook Dispatch Consumer (identity-service)

**Files to create:**
- `routify-identity-service/.../service/WebhookService.java`
- `routify-identity-service/.../service/WebhookDispatcher.java`
- `routify-identity-service/.../messaging/WebhookEventConsumer.java`

**`WebhookEventConsumer`** subscribes to:
- `KafkaTopics.ROUTE_EVENTS` → maps `RouteCreated/Activated/Deactivated/Deleted` to webhook event types
- `KafkaTopics.FILTER_EVENTS` → maps filter lifecycle events
- `KafkaTopics.CERT_EVENTS` → maps cert lifecycle events
- `KafkaTopics.AI_FILTER_DECISIONS` → maps BLOCK/FLAG decisions
- `KafkaTopics.AUDIT_EVENTS` → maps audit events

For each event:
1. Extract `tenantId` from event payload.
2. Query `WebhookSubscriptionRepository.findActiveByTenantIdAndEventType(tenantId, eventType)`.
3. For each matching subscription, enqueue a delivery via `WebhookDispatcher`.

**`WebhookDispatcher`** (async, virtual thread):
1. Build JSON payload: `{ "eventType": "...", "timestamp": "...", "data": { ... } }`.
2. Compute HMAC-SHA256 signature: `HmacUtils.hmacSha256Hex(subscription.secret, payloadJson)`.
3. POST to `subscription.url` with headers:
   - `Content-Type: application/json`
   - `X-Routify-Signature: sha256=<hex>`
   - `X-Routify-Event: <eventType>`
   - `X-Routify-Delivery: <deliveryId>`
4. On 2xx → mark `DELIVERED`, update `lastDeliveredAt`, reset `failureCount`.
5. On non-2xx or timeout → mark `PENDING`, increment `attempt`, compute `nextRetryAt` (exponential: 30s, 2min, 15min).
6. After 3 failed attempts → mark `FAILED`, increment subscription `failureCount`.
7. If `failureCount >= 10` → auto-suspend subscription (`status=SUSPENDED`), publish audit event.

**Task list:**
- [x] Create `WebhookService` with CRUD for subscriptions
- [x] Create `WebhookEventConsumer` Kafka listener
- [x] Create `WebhookDispatcher` with retry logic
- [x] Add HTTP client bean (RestClient with 5s connect timeout, 10s read timeout)
- [x] Add delivery retry scheduler (`@Scheduled`, polls PENDING deliveries with `nextRetryAt <= now()`)
- [x] Add auto-suspend logic

---

### Step 4: Kafka/RabbitMQ Integration (routify-common)

**New topology (RabbitTopology):**
```java
public static final String QUEUE_WEBHOOKS_QUERY = "routify.identity-service.webhooks.query";
public static final String RK_WEBHOOKS_QUERY    = "webhooks.query";
public static final String QUEUE_WEBHOOKS_GET   = "routify.identity-service.webhooks.get";
public static final String RK_WEBHOOKS_GET      = "webhooks.get";
public static final String QUEUE_WEBHOOKS_DELIVERIES = "routify.identity-service.webhooks.deliveries";
public static final String RK_WEBHOOKS_DELIVERIES    = "webhooks.deliveries";
```

**New command events:**
```java
record CreateWebhook(UUID commandId, UUID tenantId, String name,
    String url, List<WebhookEventType> eventTypes,
    String actor) implements CommandEvent {}
record UpdateWebhook(UUID commandId, UUID tenantId, UUID webhookId,
    String name, String url, List<WebhookEventType> eventTypes,
    String actor) implements CommandEvent {}
record DeleteWebhook(UUID commandId, UUID tenantId, UUID webhookId,
    String actor) implements CommandEvent {}
```

**Task list:**
- [x] Add RabbitTopology constants
- [x] Add CommandEvent records
- [x] Add QueryRequest/QueryResponse records
- [x] Add Kafka topic constant if using dedicated topic (or reuse USER_COMMANDS)

---

### Step 5: Admin-API Controller

**File to create:**
- `routify-admin-api/.../controller/AdminWebhooksController.java`

**Endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/webhooks` | List subscriptions (paginated) |
| `GET` | `/api/v1/admin/webhooks/{id}` | Get subscription detail |
| `POST` | `/api/v1/admin/webhooks` | Create subscription (Kafka) |
| `PUT` | `/api/v1/admin/webhooks/{id}` | Update subscription (Kafka) |
| `DELETE` | `/api/v1/admin/webhooks/{id}` | Delete subscription (Kafka) |
| `POST` | `/api/v1/admin/webhooks/{id}/test` | Send test ping (sync RPC) |
| `GET` | `/api/v1/admin/webhooks/{id}/deliveries` | Delivery log (paginated) |

**Task list:**
- [x] Create controller
- [x] Wire RabbitMQ queries and Kafka commands
- [x] Add Resilience4j wrapping
- [x] Add test-ping endpoint (sync RPC → identity-service dispatches test event)

---

### Step 6: Dashboard Module

**Files to create:**
- `routify-dashboard/src/modules/webhooks/WebhooksPage.tsx`
- `routify-dashboard/src/modules/webhooks/WebhookFormModal.tsx`
- `routify-dashboard/src/modules/webhooks/WebhookDeliveryLog.tsx`
- `routify-dashboard/src/api/webhooksApi.ts`

**UX details:**
- Subscription list with status badges (ACTIVE green, SUSPENDED amber, with failure count).
- Create/edit form: URL input, multi-select checkboxes for event types, auto-generated secret (shown once).
- "Test Ping" button sends a synthetic event and shows the response status inline.
- Delivery log tab: timeline of delivery attempts per subscription with status, response code, and error message.
- Retry button on failed deliveries.

**Task list:**
- [x] Create `webhooksApi.ts`
- [x] Add TypeScript types (`WebhookSubscriptionDto`, `WebhookDeliveryDto`, `CreateWebhookRequest`)
- [x] Create list page
- [x] Create form modal with event type checkboxes
- [x] Create delivery log component
- [x] Add route in React Router
- [x] Add sidebar navigation entry
- [x] Add MSW mock handlers

---

### Step 7: Delivery Log Retention

**Schedule:** `AuditRetentionScheduler`-style cleanup.

- `WebhookDelivery` rows older than 7 days are purged nightly.
- Configurable via `routify.webhooks.delivery-retention-days: 7`.

**Task list:**
- [x] Add scheduled cleanup method to `WebhookService` or a new scheduler
- [x] Add retention config property

---

## Acceptance Criteria

- [x] Tenant admin can create a webhook subscription for `ROUTE_ACTIVATED` events
- [x] When a route is activated, the registered URL receives a POST within 5 seconds
- [x] The POST includes a valid `X-Routify-Signature` header verifiable with the subscription secret
- [x] Failed deliveries are retried up to 3 times with exponential backoff
- [x] Subscriptions with 10+ consecutive failures are auto-suspended
- [x] Test ping from the dashboard returns success/failure inline
- [x] Delivery log shows attempt history with response codes
