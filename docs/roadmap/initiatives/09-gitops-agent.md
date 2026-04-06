# Initiative 09 — GitOps Reconciliation Agent

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 1–4 · **Owner:** Platform team  
> **Prerequisites:** Q3-07 (Import/Export), Q3-01 (API Keys), Q3-03 (Webhooks)

---

## Problem Statement

Q3 delivered YAML import/export endpoints, but applying configuration still requires a human to call the import API. CI pipelines can automate this, but each team must build their own integration. There is no out-of-the-box way to make a Git repository the single source of truth for gateway configuration.

## Solution Overview

A new standalone service (`routify-gitops-agent`) that continuously reconciles a Git repository with the live gateway configuration. On every change detected in the repo, the agent calls the admin-api import endpoint to apply the diff, then reports results via webhooks.

```
┌─ Git Repository ──────────────────────────────┐
│  routify-config/                               │
│    └── routify-export.yaml  (committed config)│
└────────────────────┬──────────────────────────┘
                     │  git pull (poll or webhook)
            ┌────────▼────────┐
            │ routify-gitops- │
            │     agent       │   API Key auth
            │  (port 8087)    ├──────────────────┐
            └────────┬────────┘                  │
                     │ POST /import/preview       │
                     │ POST /import               │
            ┌────────▼────────────────────────────▼─┐
            │          routify-admin-api              │
            │  existing Kafka commands + RabbitMQ     │
            └────────────────────────────────────────┘
```

---

## Detailed Implementation Steps

### Step 1: New Maven Module

**Files to create:**
- `routify-gitops-agent/pom.xml`
- `routify-gitops-agent/src/main/java/io/routify/gitops/RoutifyGitOpsAgentApplication.java`
- `routify-gitops-agent/Dockerfile`

**Dependencies:**
- `spring-boot-starter-web` (health endpoint only, no public API)
- `spring-boot-starter-actuator`
- `org.eclipse.jgit:org.eclipse.jgit` for Git operations
- `routify-common` for shared types
- `spring-boot-starter-data-redis` for state tracking

**Properties:**
```yaml
routify:
  gitops:
    enabled: true
    repository-url: ""          # REQUIRED: https://github.com/org/routify-config.git
    branch: main
    config-path: routify-export.yaml
    poll-interval-seconds: 60
    ssh-key-path: ""            # optional: /secrets/id_rsa
    https-username: ""          # optional: for HTTPS auth
    https-password: ""
    admin-api-url: http://localhost:8082
    api-key: ""                 # REQUIRED: API key from Q3 Initiative 01
    tenant-id: ""               # REQUIRED: tenant scope for import
    dry-run: false              # true = preview only, no apply
    webhook-url: ""             # optional: report results
    webhook-secret: ""
```

**Task list:**
- [ ] Create module with POM (parent: routify-parent)
- [ ] Add to parent POM `<modules>` list
- [ ] Create application class
- [ ] Create Dockerfile
- [ ] Add to `docker-compose.app.yml`
- [ ] Add IntelliJ run configuration in `.run/`

---

### Step 2: Git Repository Client

**Files to create:**
- `routify-gitops-agent/.../git/GitRepositoryClient.java`
- `routify-gitops-agent/.../git/GitCredentialsProvider.java`

**Behavior:**
- Clone repository on first run to a temp directory.
- On each poll cycle: `git fetch` + `git checkout origin/{branch}` (no local commits).
- Read the file at `config-path`.
- Compute SHA-256 hash of file contents.
- Compare with `lastAppliedHash` (stored in Redis: `routify:gitops:last-hash:{tenantId}`).
- If different → trigger reconciliation.

**Task list:**
- [ ] Implement JGit clone/fetch/checkout
- [ ] Support HTTPS username/password and SSH key auth
- [ ] Implement SHA-256 hash comparison
- [ ] Store last-applied hash in Redis
- [ ] Handle Git errors gracefully (network, auth, missing file)

---

### Step 3: Reconciliation Engine

**Files to create:**
- `routify-gitops-agent/.../reconcile/ReconciliationService.java`
- `routify-gitops-agent/.../reconcile/ReconciliationResult.java`

**Reconciliation flow:**
1. Read YAML file content from checked-out repo.
2. Call `POST /api/v1/admin/routes/import/preview` with API key auth.
3. If preview returns `valid: false` → log error, fire webhook with `RECONCILIATION_FAILED`.
4. If preview returns empty diff (all unchanged) → skip, update hash.
5. If `dry-run: true` → log diff, fire webhook with `DRIFT_DETECTED`, do NOT apply.
6. Otherwise → call `POST /api/v1/admin/routes/import` to apply changes.
7. On success → update `lastAppliedHash` in Redis, fire webhook with `RECONCILIATION_SUCCEEDED`.
8. On failure → fire webhook with `RECONCILIATION_FAILED`, do NOT update hash (retry on next cycle).

**Result record:**
```java
public record ReconciliationResult(
    Instant timestamp,
    String commitHash,
    String configHash,
    ReconciliationOutcome outcome,  // APPLIED, DRIFT_DETECTED, FAILED, NO_CHANGE
    int filtersCreated, int filtersUpdated,
    int routesCreated, int routesUpdated,
    List<String> warnings,
    String errorMessage
) {}
```

**Task list:**
- [ ] Implement reconciliation flow
- [ ] Implement admin-api HTTP client (RestClient with API key header)
- [ ] Implement result tracking (last 50 results in Redis list)
- [ ] Add Micrometer metrics: `routify.gitops.reconciliations` counter (tagged by outcome), `routify.gitops.latency` timer

---

### Step 4: Webhook Push Trigger (Alternative to Polling)

**File to create:**
- `routify-gitops-agent/.../controller/GitWebhookController.java`

**Endpoint:**
```
POST /api/v1/gitops/webhook
Content-Type: application/json
X-Hub-Signature-256: sha256=<hmac>   # GitHub webhook signature
```

When a push event is received for the configured branch, immediately trigger a reconciliation cycle instead of waiting for the next poll.

**Task list:**
- [ ] Create webhook endpoint
- [ ] Validate GitHub/GitLab webhook signature (HMAC-SHA256)
- [ ] Trigger reconciliation on valid push event
- [ ] Ignore events for non-configured branches

---

### Step 5: Dashboard Status Page

**Files to create:**
- `routify-dashboard/src/modules/gitops/GitOpsPage.tsx`
- `routify-dashboard/src/modules/gitops/ReconciliationHistoryTable.tsx`
- `routify-dashboard/src/api/gitopsApi.ts`

**Admin-api endpoints for agent status** (the agent exposes its own actuator, but status is also queryable from admin-api if the agent registers itself):
- `GET /api/v1/admin/gitops/status` — returns agent health, last sync, repo URL, branch, last applied commit.
- `GET /api/v1/admin/gitops/history` — returns last 50 reconciliation results.

**UX:**
- Connection status indicator (green/red).
- Repository URL, branch, last sync timestamp.
- Last applied commit hash (with link to GitHub/GitLab commit).
- Reconciliation history table with outcome badges.
- "Sync Now" button triggers immediate reconciliation.
- Drift detection banner (when dry-run mode detects changes).

**Task list:**
- [ ] Create dashboard page and API module
- [ ] Add TypeScript types for reconciliation results
- [ ] Add route in React Router
- [ ] Add sidebar navigation entry
- [ ] Add MSW mock handlers

---

## Acceptance Criteria

- [ ] Agent detects YAML changes in Git and applies them within 90 seconds of commit
- [ ] SHA-256 short-circuit prevents unnecessary import calls when config is unchanged
- [ ] Dry-run mode detects drift and fires webhook without applying changes
- [ ] Failed reconciliations do not update the last-applied hash (retry on next cycle)
- [ ] Agent authenticates to admin-api using an API key (not JWT)
- [ ] Dashboard shows reconciliation history with outcome and diff summary
- [ ] GitHub webhook push events trigger immediate reconciliation

