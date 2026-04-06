# Initiative GF-22 — Webhook Notification Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 5 (Extensions) · **Owner:** Gateway team  
> **Category:** Integration · **Priority:** Medium  
> **Filter type:** `WEBHOOK_NOTIFY`  
> **Dependencies:** Q3-03 (Webhook Notification System — for HMAC signing reuse)

---

## Problem Statement

The platform-level webhook system (Q3 Initiative 03) reacts to domain events (route activated, cert expiring, etc.). However, there is no way to fire webhooks based on individual request-level conditions — for example, alerting when a specific route returns 5xx, or when a flagged AI filter decision occurs. Operators must poll audit logs or build custom integrations.

## Solution Overview

A filter that fires a non-blocking webhook HTTP POST when a request matches configurable conditions (status codes, header values). Unlike the platform webhook system, this operates at the request level per route, enabling real-time alerting on traffic patterns.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/integration/WebhookNotifyGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `webhookUrl` | `String` | (required) | URL to POST notification |
| `secret` | `String` | (optional, sensitive) | HMAC-SHA256 signing key |
| `triggerOn` | `String` | `5xx` | Condition: `5xx`, `4xx`, `ALL`, or specific codes |
| `headerMatch` | `String` | (optional) | Header=value match condition |
| `includeRequestHeaders` | `boolean` | `false` | Include sanitized request headers |
| `includeResponseStatus` | `boolean` | `true` | Include response status |
| `maxPayloadSize` | `int` | `4096` | Max webhook payload size |

**Implementation:**
1. Evaluate conditions in the post-filter phase (after upstream response arrives).
2. Parse `triggerOn`: `5xx` = status 500-599, `4xx` = 400-499, `ALL` = any, comma-separated codes for specifics.
3. If `headerMatch` is set, check for matching header value (e.g., `X-AI-Filter-Flag=true`).
4. If conditions met → fire webhook asynchronously.

**Task list:**
- [ ] Create filter factory
- [ ] Implement condition evaluation in post-filter phase
- [ ] Parse `triggerOn` condition expressions
- [ ] Implement `headerMatch` condition

---

### Step 2: Non-Blocking Webhook Dispatch

**Implementation:**
- Use `WebClient` to POST the notification on `Schedulers.boundedElastic()`.
- Fire-and-forget: never block the response to the client.
- Log errors at WARN level (webhook failure must not affect the response).
- Timeout: 5 seconds for webhook call.

**Task list:**
- [ ] Implement non-blocking `WebClient` POST
- [ ] Fire-and-forget dispatch (subscribe and forget)
- [ ] 5-second timeout
- [ ] Error logging without affecting client response

---

### Step 3: HMAC Signing

**Implementation:**
Reuse the same `X-Routify-Signature` pattern from the platform webhook system (Q3-03):
```
X-Routify-Signature: sha256=<hmac-sha256(body, secret)>
```

If `secret` is not configured, skip the signature header.

**Task list:**
- [ ] Compute HMAC-SHA256 of payload body using `secret`
- [ ] Set `X-Routify-Signature` header
- [ ] Skip signing when `secret` is empty
- [ ] Mark `secret` as `@SensitiveField`

---

### Step 4: Webhook Payload

**JSON payload structure:**
```json
{
  "routeId": "uuid",
  "correlationId": "uuid",
  "method": "POST",
  "path": "/api/users",
  "status": 503,
  "elapsedMs": 1234,
  "timestamp": "2026-07-15T10:30:00Z",
  "headers": {
    "X-AI-Filter-Flag": "true"
  }
}
```

- `headers` included only when `includeRequestHeaders=true` (sanitized via the global `REDACTED_HEADERS` list).
- Payload truncated to `maxPayloadSize`.

**Task list:**
- [ ] Build JSON payload with request metadata
- [ ] Conditionally include sanitized request headers
- [ ] Truncate payload to `maxPayloadSize`

---

### Step 5: Rate Limiting (Cooldown)

**Implementation:**
- Max 1 webhook per route per `cooldownSeconds` (default 10).
- Use an in-memory `Map<routeId, Instant>` to track last notification timestamp.
- If within cooldown → skip notification (log at DEBUG level).
- Prevents notification storms under high error rates.

**Additional config param:**
```yaml
cooldownSeconds: 10  # default
```

**Task list:**
- [ ] Implement per-route cooldown
- [ ] Skip notifications within cooldown window
- [ ] Add `cooldownSeconds` config parameter

---

### Step 6: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `WEBHOOK_NOTIFY`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [ ] Add `WEBHOOK_NOTIFY` to `FilterType` enum
- [ ] Add to TypeScript `FilterType` union
- [ ] Add filter config form in dashboard (with secret input)

---

### Step 7: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/integration/WebhookNotifyTest.java`

**Test cases:**
- 5xx response with `triggerOn=5xx` → webhook fired
- 200 response with `triggerOn=5xx` → webhook NOT fired
- `triggerOn=ALL` → webhook fired on any status
- `triggerOn=503,504` → only specific codes trigger
- `headerMatch=X-Flag=true` → webhook fired when header matches
- HMAC signature computed correctly
- No `secret` → no signature header
- Cooldown prevents duplicate notifications within window
- Webhook failure does not affect client response
- Fire-and-forget: response returned before webhook completes

**Task list:**
- [ ] Write condition evaluation tests
- [ ] Write HMAC signing tests
- [ ] Write cooldown tests
- [ ] Write fire-and-forget behavior tests

---

## Acceptance Criteria

- [ ] Non-blocking webhook POST on configurable request conditions
- [ ] Status code matching (`5xx`, `4xx`, `ALL`, specific codes)
- [ ] Header value matching condition
- [ ] HMAC-SHA256 signing with `X-Routify-Signature`
- [ ] Per-route cooldown prevents notification storms
- [ ] Webhook failure never affects client response
- [ ] Fire-and-forget dispatch

