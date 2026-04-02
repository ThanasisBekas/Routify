#!/usr/bin/env bash
# setup-branch-protection.sh
# Configures GitHub branch protection rules for Routify.
#
# Prerequisites: gh auth login
# Usage: ./setup-branch-protection.sh

set -euo pipefail

REPO="ThanasisBekas/Routify"
OWNER="ThanasisBekas"

echo "=== Setting default branch to develop ==="
gh repo edit "$REPO" --default-branch develop

echo ""
echo "=== Protecting 'develop' branch ==="
gh api --method PUT "repos/${REPO}/branches/develop/protection" \
  --input - <<'EOF'
{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF

echo ""
echo "=== Protecting 'master' branch ==="
gh api --method PUT "repos/${REPO}/branches/master/protection" \
  --input - <<'EOF'
{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF

echo ""
echo "=== Protecting 'release/**' branches ==="
# Ruleset-based protection for release/* pattern (requires GitHub Free/Pro with rulesets)
gh api --method POST "repos/${REPO}/rulesets" \
  --input - <<'EOF'
{
  "name": "Protect release branches",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [
    {
      "actor_id": 5,
      "actor_type": "RepositoryRole",
      "bypass_mode": "always"
    }
  ],
  "conditions": {
    "ref_name": {
      "include": ["refs/heads/release/**"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      }
    },
    { "type": "deletion" },
    { "type": "non_fast_forward" }
  ]
}
EOF

echo ""
echo "=== Done! ==="
echo ""
echo "Summary:"
echo "  - Default branch: develop"
echo "  - develop: PRs required, admin can bypass"
echo "  - master: PRs required, admin can bypass"
echo "  - release/*: PRs required, admin can bypass"
echo ""
echo "You (${OWNER}) can push directly to any branch."
echo "All other contributors must create pull requests."
echo "Only you can approve and merge them."

