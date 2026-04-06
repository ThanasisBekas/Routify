# Routify — Release Process

This document describes the complete release lifecycle for Routify, from cutting a release to promoting it through environments to production.

> **All workflows create Pull Requests** instead of pushing directly to protected branches (`master`, `develop`). This ensures code review, CI checks, and audit trails for every change.

## Overview

```
develop ──→ Release Workflow ──→ release/N branch ──→ Promote (staging) ──→ Promote (production)
                 │                     │                                           │
            PR: snapshot bump     Docker images                        PR: release → master
            to develop            published to GHCR                    PR: back-merge → develop
                                                                       Tag + GitHub Release
```

### Workflows

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| **Release** | Manual (`workflow_dispatch`) | Cut a release branch, build, test, publish Docker images, open PR to bump develop |
| **Promote** | Manual (`workflow_dispatch`) | Promote a release to staging or production (PRs for merge + back-merge) |
| **Hotfix** | Manual (`workflow_dispatch`) | Emergency fix on a released version (PRs for merge + back-merge) |
| **Bump Version** | Manual (`workflow_dispatch`) | Open PR to bump major or minor version on develop |
| **Generate .env** | Manual (`workflow_dispatch`) | Open PR to add/rotate branch-scoped env file on develop |
| **CI** | Push / PR | Continuous integration (compile, test, lint) |

---

## Standard Release Flow

### 1. Cut a Release

Go to **Actions → Release → Run workflow**:

| Input | Example | Description |
|-------|---------|-------------|
| `release_number` | `123` | Creates `release/123` branch, version `2.0.123` |
| `pre_release` | `none` / `rc` / `beta` | Pre-release qualifier |
| `rc_number` | `1` | Iteration for RC/beta (e.g. `rc.1`) |
| `skip_tests` | `false` | Emergency only — skips full test suite |

**What happens:**
1. ✅ Release branch `release/123` created from `develop`
2. ✅ Maven + dashboard version bumped to `2.0.123`
3. ✅ Full backend unit tests + frontend (typecheck, lint, unit, E2E) run
4. ✅ 8 Docker images built and pushed to GHCR
5. ✅ Draft GitHub Release created with build manifest
6. 📋 **PR opened** to bump develop to `2.0.123-SNAPSHOT` ← *requires review & merge*
7. ✅ Automatic rollback if any step fails

### 2. Promote to Staging

Go to **Actions → Promote Release → Run workflow**:

| Input | Example | Description |
|-------|---------|-------------|
| `release_number` | `123` | The release to promote |
| `target_environment` | `staging` | Target environment |

**What happens:**
1. ✅ Validates release branch and Docker images exist in GHCR
2. ✅ Re-tags all 8 images with `-staging` suffix
3. ✅ Creates a GitHub Deployment record for `staging`
4. ❌ Does NOT merge to master (release stays on its branch)

### 3. Promote to Production

Go to **Actions → Promote Release → Run workflow**:

| Input | Example | Description |
|-------|---------|-------------|
| `release_number` | `123` | The release to promote |
| `target_environment` | `production` | Target environment |

**What happens:**
1. ⏳ **Waits for manual approval** (configured in GitHub Environment protection rules)
2. ✅ Re-tags Docker images with `-production` suffix + `stable` tag
3. 📋 **PR opened** to merge `release/123` into `master` ← *requires review & merge*
4. ✅ Creates annotated tag `v2.0.123`
5. ✅ Publishes the GitHub Release (removes draft status)
6. 📋 **PR opened** to back-merge into `develop` ← *requires review & merge*
7. ✅ Creates a GitHub Deployment record for `production`

**After the workflow completes, you need to:**
1. Merge the release → master PR
2. Merge the back-merge → develop PR
3. Delete the `release/123` branch

---

## Pre-Release (RC / Beta) Flow

For releases that need staging validation before a final cut:

```
Release (rc.1) → Staging validate → Release (rc.2) → Staging validate → Release (final) → Production
```

1. Run **Release** with `pre_release=rc`, `rc_number=1` → publishes `2.0.123-rc.1`
2. Merge the develop snapshot bump PR
3. Promote to staging, validate
4. If issues found: push fixes to `release/123`, re-run Release with `rc_number=2`
5. When ready: run Release with `pre_release=none` for the final version
6. Promote to production, merge the resulting PRs

---

## Hotfix Flow

For emergency fixes on already-released versions:

### 1. Create Hotfix Branch

Go to **Actions → Hotfix → Run workflow**:

| Input | Example | Description |
|-------|---------|-------------|
| `base_version` | `2.0.123` | The released version to hotfix (must have `v2.0.123` tag) |
| `hotfix_number` | `1` | Creates version `2.0.123.1` |
| `mode` | `create` | Creates the hotfix branch |

### 2. Push the Fix

```bash
git fetch origin hotfix/2.0.123
git checkout hotfix/2.0.123
# ... make your fix ...
git commit -m "fix: critical bug in route matching"
git push origin hotfix/2.0.123
```

### 3. Apply the Hotfix

Re-run the **Hotfix** workflow with `mode=apply`:
- Runs full test suite
- Builds and publishes Docker images
- Creates tag `v2.0.123.1` and GitHub Release
- 📋 **PR opened** to merge hotfix into `master`
- 📋 **PR opened** to back-merge hotfix into `develop`

**After the workflow completes, merge both PRs and delete the hotfix branch.**

---

## Docker Image Tags

All images are published to `ghcr.io/<owner>/routify-<service>`.

| Tag | When | Example |
|-----|------|---------|
| `<version>` | Every release | `2.0.123`, `2.0.123-rc.1` |
| `<major>.<minor>` | Stable releases only | `2.0` |
| `latest` | Stable releases only | `latest` |
| `<version>-staging` | Staging promotion | `2.0.123-staging` |
| `<version>-production` | Production promotion | `2.0.123-production` |
| `stable` | Production promotion | `stable` |

### Pull Images

```bash
# Latest stable
docker pull ghcr.io/<owner>/routify-api-gateway:stable

# Specific version
docker pull ghcr.io/<owner>/routify-api-gateway:2.0.123

# Staging
docker pull ghcr.io/<owner>/routify-api-gateway:2.0.123-staging
```

---

## Versioning Strategy

| Component | Format | Example |
|-----------|--------|---------|
| Release | `MAJOR.MINOR.RELEASE` | `2.0.123` |
| Pre-release | `MAJOR.MINOR.RELEASE-TYPE.N` | `2.0.123-rc.1` |
| Hotfix | `MAJOR.MINOR.RELEASE.HOTFIX` | `2.0.123.1` |
| Develop | `MAJOR.MINOR.RELEASE-SNAPSHOT` | `2.0.123-SNAPSHOT` |

**Bump version** (between release cycles):
- **Minor**: `2.0.x` → `2.1.0-SNAPSHOT` — run **Bump Version** workflow, merge the PR
- **Major**: `2.x.y` → `3.0.0-SNAPSHOT` — run **Bump Version** workflow, merge the PR

---

## GitHub Environment Setup

### Required Configuration

Go to **Settings → Environments** and create:

#### `staging`
- No protection rules required (optional: add deployment branch restrictions)

#### `production`
- ✅ **Required reviewers**: Add team leads / release managers
- ✅ **Wait timer** (optional): Add a delay before deployment starts
- ✅ **Deployment branches**: Restrict to `release/**` and `hotfix/**` patterns

---

## Build Manifest

Every release produces a `build-manifest.json` attached to the GitHub Release:

```json
{
  "version": "2.0.123",
  "ref": "release/123",
  "timestamp": "2026-04-06T12:00:00Z",
  "images": {
    "routify-api-gateway": "sha256:abc123...",
    "routify-admin-api": "sha256:def456...",
    "routify-identity-service": "sha256:...",
    "routify-route-service": "sha256:...",
    "routify-audit-service": "sha256:...",
    "routify-cert-vault": "sha256:...",
    "routify-ai-service": "sha256:...",
    "routify-dashboard": "sha256:..."
  }
}
```

Use this manifest for audit trails and to verify image integrity.

---

## Troubleshooting

### Release branch already exists
Delete it manually: `git push origin --delete release/123`, then re-run.

### Docker image verification fails during promotion
Ensure the Release workflow completed successfully. Check the GHCR packages in the repository.

### Production promotion PR has merge conflicts
Resolve conflicts locally on the release branch and push:
```bash
git checkout release/N
git merge origin/master
# resolve conflicts
git push origin release/N
```
Then the PR will be mergeable.

### Back-merge PR has conflicts
Resolve locally:
```bash
git checkout chore/backmerge-vX.Y.Z
git merge origin/develop
# resolve conflicts
git push origin chore/backmerge-vX.Y.Z
```
