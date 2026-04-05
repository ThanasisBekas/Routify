# Initiative 12 — AI Policy Playground & Prompt Versioning

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 4–7 · **Owner:** AI-service + Admin-API + Dashboard teams  
> **Prerequisites:** Existing `AiFilterEvaluationService`, `VerdictCacheService`, `PromptBuilderService`, Q3-05 (Health Dashboard)

---

## Problem Statement

AI filter and modifier prompts are stored as raw strings in filter config. Changing a prompt requires updating the filter definition — there is no version history, no way to compare accuracy between versions, and no safe rollout mechanism. Operators iterate blindly: change prompt → deploy → hope for the best.

## Solution Overview

Add prompt versioning with a visual playground for interactive testing, historical accuracy scoring based on operator-labelled ground truth, and A/B split testing between prompt versions at the gateway.

---

## Detailed Implementation Steps

### Step 1: Prompt Version Model (audit-service)

**Files to create:**
- `routify-audit-service/src/main/resources/db/migration/V{next}__ai_prompt_versions.sql`
- `routify-audit-service/.../domain/AiPromptVersion.java`
- `routify-audit-service/.../repository/AiPromptVersionRepository.java`

**Schema (audit-service owns this — it's analytics/compliance data, not route config):**
```sql
CREATE TABLE routify_audit.ai_prompt_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filter_id       UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    version         INT NOT NULL,
    prompt_text     TEXT NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | ACTIVE | ARCHIVED
    accuracy_score  DECIMAL(5,2),   -- computed, NULL until enough labels
    total_decisions INT NOT NULL DEFAULT 0,
    correct_count   INT NOT NULL DEFAULT 0,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at    TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ,
    UNIQUE (filter_id, version)
);
CREATE INDEX idx_prompt_version_filter ON routify_audit.ai_prompt_version(filter_id, status);
```

**Task list:**
- [ ] Create Flyway migration
- [ ] Create entity and repository
- [ ] Create MapStruct mapper

---

### Step 2: Prompt Version Management (audit-service + admin-api)

**RabbitMQ topology additions:**
```java
QUEUE_AI_PROMPT_VERSIONS_QUERY = "routify.audit-service.ai-prompt.versions.query";
QUEUE_AI_PROMPT_VERSIONS_GET   = "routify.audit-service.ai-prompt.versions.get";
QUEUE_AI_PROMPT_VERSIONS_SAVE  = "routify.audit-service.ai-prompt.versions.save";
```

**Admin-api endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/ai-filter/{filterId}/versions` | List versions |
| `GET` | `/api/v1/admin/ai-filter/{filterId}/versions/{versionId}` | Version detail |
| `POST` | `/api/v1/admin/ai-filter/{filterId}/versions` | Create draft version |
| `POST` | `/api/v1/admin/ai-filter/{filterId}/versions/{versionId}/activate` | Activate version |
| `POST` | `/api/v1/admin/ai-filter/{filterId}/versions/{versionId}/archive` | Archive version |

**Activation flow:** Activating a version archives the currently active one and updates the filter's config `promptText` to the new version's text (via Kafka `UpdateFilter` command to route-service).

**Task list:**
- [ ] Add RabbitMQ topology constants
- [ ] Implement query handlers in audit-service
- [ ] Create admin-api controller endpoints
- [ ] Implement activation → filter config update pipeline

---

### Step 3: Interactive Playground (dashboard)

**Files to create:**
- `routify-dashboard/src/modules/ai/AiPlaygroundPage.tsx`
- `routify-dashboard/src/modules/ai/components/PromptEditor.tsx`
- `routify-dashboard/src/modules/ai/components/TestRequestBuilder.tsx`
- `routify-dashboard/src/modules/ai/components/VerdictDisplay.tsx`

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  AI Policy Playground                          [Filter ▼]   │
├───────────────────────────────┬──────────────────────────────┤
│  Prompt Editor               │  Test Request Builder         │
│  ┌─────────────────────────┐ │  ┌────────────────────────┐  │
│  │ Block requests that      │ │  │ Method: POST           │  │
│  │ contain SQL injection    │ │  │ Path: /api/orders      │  │
│  │ patterns or attempt to   │ │  │ Headers: ...           │  │
│  │ access admin endpoints   │ │  │ Body: {"query":"..."}  │  │
│  │ without proper auth...   │ │  └────────────────────────┘  │
│  └─────────────────────────┘ │  [Run Test]                   │
│  Version: v4 (DRAFT)         │                               │
│  [Save Draft] [Activate]     ├──────────────────────────────┤
│                               │  Verdict:                    │
│  Version History:             │  ✅ ALLOW (confidence: 0.92) │
│  v4 DRAFT   - current        │  Reason: "Request is a       │
│  v3 ACTIVE  - since Jul 10   │  standard order creation..."  │
│  v2 ARCHIVED                  │  Latency: 340ms              │
│  v1 ARCHIVED                  │  Cached: false               │
└───────────────────────────────┴──────────────────────────────┘
```

**"Run Test"** calls existing `POST /api/v1/admin/ai-filter/test-policy` with the draft prompt text (not the saved filter config).

**Task list:**
- [ ] Create playground page with split-pane layout
- [ ] Create prompt editor with syntax highlighting (monospace textarea)
- [ ] Create test request builder (method, path, headers, body fields)
- [ ] Create verdict display component
- [ ] Wire to existing test-policy endpoint (add `promptOverride` parameter)
- [ ] Create version history sidebar
- [ ] Add React Router route
- [ ] Add sidebar navigation entry

---

### Step 4: Ground-Truth Labelling

**Files to create:**
- `routify-audit-service/.../domain/AiDecisionLabel.java`

**Admin-api endpoint:**
```
POST /api/v1/admin/ai-filter/decisions/{evaluationId}/label
Body: { "label": "CORRECT" | "INCORRECT" | "UNCLEAR" }
```

**Logic:**
1. Store label in `ai_filter_decision` table (add `operator_label` column via Flyway migration).
2. Recalculate accuracy for the prompt version used for that decision.
3. Update `ai_prompt_version.accuracy_score = correct_count / total_labelled`.

**Dashboard integration:** In the AI Filter Stats page (`AiFilterStatsPage.tsx`), each decision row gets a thumbs-up/thumbs-down button for quick labelling.

**Task list:**
- [ ] Add `operator_label` column to `ai_filter_decision` table (Flyway)
- [ ] Create labelling endpoint
- [ ] Implement accuracy recalculation
- [ ] Add labelling buttons to AI filter stats page

---

### Step 5: A/B Split Testing

**Files to modify:**
- `routify-api-gateway/.../filter/AiGatewayFilterFactory.java`

**New config parameter on `AI_FILTER` type:**
```json
{
  "policy": "Block SQL injection and XSS attacks",
  "fallbackAction": "ALLOW",
  "promptVersionSplit": {
    "v3": 90,
    "v4": 10
  }
}
```

**Gateway behavior:**
1. If `promptVersionSplit` is absent → use `policy` field (existing behavior).
2. If present → randomly select a version based on weights.
3. Fetch the prompt text for the selected version (cached in gateway memory, refreshed on filter events).
4. Tag the AI service RPC request with `promptVersion` field.
5. The `AiFilterDecisionEvent` published to Kafka includes `promptVersion` for audit attribution.

**Task list:**
- [ ] Add `promptVersionSplit` config parsing to `AiGatewayFilterFactory`
- [ ] Implement weighted version selection
- [ ] Cache prompt version texts in gateway (populated from filter config events)
- [ ] Pass version ID to ai-service RPC
- [ ] Include version ID in `AiFilterDecisionEvent`
- [ ] Add version-tagged accuracy comparison to dashboard

---

### Step 6: Comparison Dashboard

**Files to create:**
- `routify-dashboard/src/modules/ai/VersionComparisonPage.tsx`

**UX:** Side-by-side charts for two selected prompt versions:
- Accuracy score line chart (over time as more labels come in)
- Block rate bar chart
- Average latency comparison
- Confidence distribution histogram
- "Promote" button to activate the better-performing version

**Task list:**
- [ ] Create comparison page
- [ ] Add version selection dropdowns
- [ ] Add accuracy/latency/block-rate charts (Recharts)
- [ ] Wire to audit-service stats endpoints (extend existing `QUEUE_AUDIT_AI_FILTER_STATS` with version filter)

---

## Acceptance Criteria

- [ ] Operator can create a new prompt version, test it in the playground, and see the verdict
- [ ] Activating a version updates the live filter config without manual filter editing
- [ ] A/B split testing routes 10% of traffic to a new prompt version
- [ ] Operator can label decisions as correct/incorrect, and accuracy score updates
- [ ] Version comparison dashboard shows side-by-side metrics for two versions
- [ ] Archived versions are preserved for auditing but cannot be activated

