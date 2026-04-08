# routify-gitops-agent — Agent Guide

GitOps sync agent that reconciles route/filter configuration from a Git repository into the Routify platform via the admin-api.

## Runtime

- **Port:** 8087 (actuator: 9087)
- **Runtime:** Spring Web with Virtual Threads
- **No database** — uses Redis for sync state tracking

## Package Layout

```
io.routify.gitops/
├── config/       # GitOps agent configuration (poll interval, repository URL, credentials)
├── controller/   # Webhook endpoint for push-based triggers
├── git/          # GitRepositoryClient, GitCredentialsProvider (SSH/HTTPS)
└── reconcile/    # ReconciliationService, ReconciliationResult, AdminApiClient, WebhookNotifier
```

## Key Patterns

### Reconciliation flow
```
Git repo poll (or webhook trigger)
  → GitRepositoryClient.pull() (clone/fetch latest)
  → Parse YAML config file (routify-export.yaml)
  → ReconciliationService.reconcile()
    → Diff desired state vs current state (via AdminApiClient → admin-api REST)
    → Apply changes (create/update/delete routes + filters via admin-api)
  → WebhookNotifier (optional notification on sync result)
```

### Configuration
Key env vars:
- `GITOPS_REPOSITORY_URL` — Git repo URL (SSH or HTTPS)
- `GITOPS_BRANCH` — branch to track (default: `main`)
- `GITOPS_CONFIG_PATH` — path to config file in repo (default: `routify-export.yaml`)
- `GITOPS_POLL_INTERVAL_SECONDS` — polling interval (default: 60s)
- `GITOPS_SSH_KEY_PATH` / `GITOPS_HTTPS_USERNAME` + `GITOPS_HTTPS_PASSWORD` — credentials
- `GITOPS_ADMIN_API_URL` — admin-api base URL (e.g. `http://admin-api:8082`)
- `GITOPS_API_KEY` — API key for admin-api authentication
- `GITOPS_DRY_RUN` — preview changes without applying (default: `false`)

### Config file format
Uses the same YAML format as the admin-api export endpoint (see `docs/schema/route-export-v1.yaml`).

## Build

```bash
mvn clean package -pl routify-gitops-agent -am -DskipTests
```

