# Initiative 15 — Platform Alerting Engine

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 8–11 · **Owner:** Audit-service + Admin-API teams  
> **Prerequisites:** Q3-03 (Webhooks for delivery), Q3-05 (SLO model for alert templates)

---

## Problem Statement

Operators rely on Grafana/Prometheus alerting rules for notifications about gateway health issues. This requires Prometheus expertise and infrastructure access that tenant admins don't have. There is no way to set up alerts from within the Routify dashboard. SLO violations (Q3-05) are visible on the dashboard but don't proactively notify anyone.

## Solution Overview

A native alerting engine in audit-service that evaluates threshold rules against platform metrics on a 60-second cycle. Alerts transition through a state machine (OK → PENDING → FIRING → RESOLVED) and deliver notifications via the webhook infrastructure. Operators manage alert rules entirely from the dashboard.

---

## Detailed Implementation Steps

### Step 1: Alert Rule Model (audit-service)

**Files to create:**
- `routify-audit-service/src/main/resources/db/migration/V{next}__alert_rules.sql`
- `routify-audit-service/.../domain/AlertRule.java`
- `routify-audit-service/.../domain/AlertEvent.java`
- `routify-audit-service/.../repository/AlertRuleRepository.java`
- `routify-audit-service/.../repository/AlertEventRepository.java`

**Schema:**
```sql
CREATE TABLE routify_audit.alert_rule (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         VARCHAR(1000),
    metric              VARCHAR(50) NOT NULL,   -- ERROR_RATE, P99_LATENCY, DLQ_DEPTH, CERT_EXPIRY_DAYS, QUOTA_USAGE, SLO_BUDGET
    route_id            UUID,                    -- optional, NULL = global
    operator            VARCHAR(10) NOT NULL,    -- GT, LT, GTE, LTE, EQ
    threshold           DECIMAL(12,4) NOT NULL,
    window_minutes      INT NOT NULL DEFAULT 5,
    cooldown_minutes    INT NOT NULL DEFAULT 30,
    severity            VARCHAR(20) NOT NULL DEFAULT 'WARNING',  -- INFO, WARNING, CRITICAL
    enabled             BOOLEAN NOT NULL DEFAULT true,
    current_state       VARCHAR(20) NOT NULL DEFAULT 'OK',       -- OK, PENDING, FIRING
    state_changed_at    TIMESTAMPTZ,
    consecutive_breaches INT NOT NULL DEFAULT 0,
    last_evaluated_at   TIMESTAMPTZ,
    last_fired_at       TIMESTAMPTZ,
    created_by          VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE routify_audit.alert_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id         UUID NOT NULL REFERENCES routify_audit.alert_rule(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,
    transition      VARCHAR(30) NOT NULL,   -- OK_TO_PENDING, PENDING_TO_FIRING, FIRING_TO_OK
    metric_value    DECIMAL(12,4),
    threshold       DECIMAL(12,4),
    message         VARCHAR(1000),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_alert_rule_tenant ON routify_audit.alert_rule(tenant_id, enabled);
CREATE INDEX idx_alert_event_rule ON routify_audit.alert_event(rule_id, occurred_at DESC);
```

**Metric enum (routify-common):**
```java
public enum AlertMetric {
    ERROR_RATE,         // % of 5xx responses in window
    P99_LATENCY,        // milliseconds
    DLQ_DEPTH,          // number of unprocessed DLQ events
    CERT_EXPIRY_DAYS,   // days until cert expires
    QUOTA_USAGE,        // % of monthly request quota consumed
    SLO_BUDGET,         // % of SLO error budget consumed
    REQUEST_VOLUME,     // requests per minute (spike/drop detection)
    AUTH_FAILURE_RATE   // % of auth failures in window
}
```

**Task list:**
- [ ] Create Flyway migration
- [ ] Create `AlertRule` entity
- [ ] Create `AlertEvent` entity
- [ ] Create repositories
- [ ] Create `AlertMetric` enum in `routify-common`

---

### Step 2: Alert Evaluation Scheduler (audit-service)

**File to create:**
- `routify-audit-service/.../scheduler/AlertEvaluationScheduler.java`

**Evaluation cycle (every 60s):**
```java
@Scheduled(fixedDelayString = "${routify.alerts.evaluation-interval-ms:60000}")
public void evaluate() {
    List<AlertRule> enabledRules = alertRuleRepository.findByEnabledTrue();
    for (AlertRule rule : enabledRules) {
        double currentValue = metricResolver.resolve(rule);
        boolean breached = isBreached(rule, currentValue);
        transitionState(rule, breached, currentValue);
    }
}
```

**State machine:**
```
                  breached
    OK ─────────────────────► PENDING
    ▲                              │ breached for N consecutive checks
    │ resolved                     ▼
    └──────────────────────── FIRING
                               │ resolved
                               ▼
                              OK
```

- `OK → PENDING`: threshold breached once.
- `PENDING → FIRING`: breached for `ceil(windowMinutes / evaluationInterval)` consecutive checks.
- `FIRING → OK`: metric drops below threshold.
- `OK → PENDING → OK`: transient spike, auto-clears.
- Cooldown: after `FIRING → OK`, suppress re-firing for `cooldownMinutes`.

**On FIRING:**
1. Persist `AlertEvent` with transition `PENDING_TO_FIRING`.
2. Publish to `KafkaTopics.AUDIT_EVENTS` with type `ALERT_FIRED`.
3. Webhook dispatch picks it up (Q3 webhook infrastructure) and delivers to subscribed endpoints.

**On RESOLVED:**
1. Persist `AlertEvent` with transition `FIRING_TO_OK`.
2. Publish `ALERT_RESOLVED` event.

**Task list:**
- [ ] Create evaluation scheduler
- [ ] Implement state machine logic
- [ ] Implement cooldown tracking
- [ ] Publish alert events to Kafka

---

### Step 3: Metric Resolver (audit-service)

**File to create:**
- `routify-audit-service/.../scheduler/AlertMetricResolver.java`

**Resolution per metric type:**

| Metric | Data Source | Query |
|--------|------------|-------|
| `ERROR_RATE` | `request_log` | `COUNT(status >= 500) / COUNT(*) * 100` in window |
| `P99_LATENCY` | `request_log` | `PERCENTILE_DISC(0.99) WITHIN GROUP (ORDER BY duration_ms)` in window |
| `DLQ_DEPTH` | `dlq_event` | `COUNT(*) WHERE failed_at > NOW() - window` |
| `CERT_EXPIRY_DAYS` | `cert-vault` RPC | Min `daysUntilExpiry` across active certs |
| `QUOTA_USAGE` | Redis | `GET routify:quota:{tenantId}:{YYYY-MM}` / `plan.monthlyRequestQuota * 100` |
| `SLO_BUDGET` | Computed | `(errorCount / allowedErrors) * 100` from route SLO |
| `REQUEST_VOLUME` | `request_log` | `COUNT(*) / windowMinutes` in window |
| `AUTH_FAILURE_RATE` | `request_log` | `COUNT(status = 401 OR status = 403) / COUNT(*) * 100` in window |

**Task list:**
- [ ] Implement resolver for each metric type
- [ ] Add route-scoped filtering (when `routeId` is set on the rule)
- [ ] Cache cert expiry data to avoid RPC on every evaluation cycle
- [ ] Add `routify.alerts.evaluation` timer to `RoutifyMetrics`

---

### Step 4: RabbitMQ Query Handlers + Admin-API Endpoints

**RabbitMQ topology:**
```java
QUEUE_ALERT_RULES_QUERY = "routify.audit-service.alert-rules.query";
QUEUE_ALERT_RULES_GET   = "routify.audit-service.alert-rules.get";
QUEUE_ALERT_EVENTS_QUERY = "routify.audit-service.alert-events.query";
```

**Admin-api endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/alerts` | List alert rules (paginated) |
| `GET` | `/api/v1/admin/alerts/{id}` | Rule detail with current state |
| `POST` | `/api/v1/admin/alerts` | Create rule (Kafka command) |
| `PUT` | `/api/v1/admin/alerts/{id}` | Update rule (Kafka command) |
| `DELETE` | `/api/v1/admin/alerts/{id}` | Delete rule (Kafka command) |
| `POST` | `/api/v1/admin/alerts/{id}/mute` | Mute for N minutes |
| `POST` | `/api/v1/admin/alerts/{id}/unmute` | Unmute |
| `GET` | `/api/v1/admin/alerts/{id}/history` | State transition history |

**Task list:**
- [ ] Add RabbitMQ topology constants
- [ ] Implement query handlers in audit-service
- [ ] Create admin-api controller
- [ ] Add Kafka command types for alert CRUD

---

### Step 5: Dashboard Module

**Files to create:**
- `routify-dashboard/src/modules/alerts/AlertsPage.tsx`
- `routify-dashboard/src/modules/alerts/AlertRuleFormModal.tsx`
- `routify-dashboard/src/modules/alerts/AlertHistoryTimeline.tsx`
- `routify-dashboard/src/api/alertsApi.ts`

**UX:**
- Rule list with state indicator (green OK / amber PENDING / red FIRING).
- Severity badges (INFO blue, WARNING amber, CRITICAL red).
- Create/edit form: metric dropdown, optional route picker, operator selector, threshold input, window/cooldown inputs, severity selector.
- Alert history timeline showing state transitions with metric values.
- "Mute" toggle with duration selector (15m, 1h, 4h, 24h).
- Pre-built template buttons: "SLO budget alert", "Cert expiry alert", "DLQ depth alert".

**Task list:**
- [ ] Create alerts list page
- [ ] Create rule form modal
- [ ] Create history timeline component
- [ ] Add template buttons for common alert patterns
- [ ] Add TypeScript types and API functions
- [ ] Add route in React Router and sidebar entry
- [ ] Add MSW mock handlers

---

### Step 6: Webhook Integration

**New webhook event types:**
```java
ALERT_FIRED,      // alert transitioned to FIRING
ALERT_RESOLVED    // alert transitioned back to OK
```

**Webhook payload:**
```json
{
  "eventType": "ALERT_FIRED",
  "alert": {
    "ruleId": "...",
    "ruleName": "Orders API error rate",
    "metric": "ERROR_RATE",
    "routeId": "...",
    "routeName": "orders-api",
    "threshold": 5.0,
    "currentValue": 7.3,
    "severity": "CRITICAL",
    "windowMinutes": 5
  },
  "timestamp": "2026-08-15T14:22:00Z"
}
```

**Task list:**
- [ ] Add event types to `WebhookEventType`
- [ ] Format alert webhook payload
- [ ] Test webhook delivery on alert state transitions

---

## Acceptance Criteria

- [ ] An alert rule for "ERROR_RATE > 5% for 5 minutes on route X" fires correctly
- [ ] Alert transitions through OK → PENDING → FIRING → OK with correct state machine behavior
- [ ] Webhook notification delivered within 60s of alert firing
- [ ] Muted alerts do not fire notifications during mute window
- [ ] Cooldown prevents alert from immediately re-firing after resolution
- [ ] Dashboard shows alert state in real-time with history timeline
- [ ] Pre-built templates create rules with sensible defaults

