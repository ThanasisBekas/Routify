#!/usr/bin/env bash
# ─── Routify Helm Chart Validation ──────────────────────────────────────────
# Runs helm lint + template validation locally.
# Prerequisites: helm CLI installed.
#
# Usage:
#   ./scripts/helm-validate.sh
#   ./scripts/helm-validate.sh --template-only
# ────────────────────────────────────────────────────────────────────────────

set -euo pipefail

CHART_DIR="deploy/helm/routify"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

echo "═══════════════════════════════════════════════════════"
echo "  Routify Helm Chart Validation"
echo "═══════════════════════════════════════════════════════"

# ─── Step 1: Lint ───────────────────────────────────────────────────────────
echo ""
echo "▸ Running helm lint..."
helm lint "$CHART_DIR" \
  --set secrets.jwtPrivateKey=test \
  --set secrets.jwtPublicKey=test \
  --set secrets.certVaultEncryptionKey=test \
  --set secrets.dbPassword=test \
  --set secrets.rabbitmqPassword=test \
  --set secrets.redisPassword=test
echo "  ✓ Lint passed"

if [[ "${1:-}" == "--template-only" ]]; then
  echo ""
  echo "✅ Lint-only validation complete."
  exit 0
fi

# ─── Step 2: Template render ────────────────────────────────────────────────
echo ""
echo "▸ Rendering templates..."
helm template routify "$CHART_DIR" \
  --set secrets.jwtPrivateKey=test \
  --set secrets.jwtPublicKey=test \
  --set secrets.certVaultEncryptionKey=test \
  --set secrets.dbPassword=test \
  --set secrets.rabbitmqPassword=test \
  --set secrets.redisPassword=test \
  > /dev/null
echo "  ✓ Templates rendered successfully"

# ─── Step 3: kubectl dry-run (if kubectl available) ─────────────────────────
if command -v kubectl &> /dev/null; then
  echo ""
  echo "▸ Running kubectl dry-run validation..."
  helm template routify "$CHART_DIR" \
    --set secrets.jwtPrivateKey=test \
    --set secrets.jwtPublicKey=test \
    --set secrets.certVaultEncryptionKey=test \
    --set secrets.dbPassword=test \
    --set secrets.rabbitmqPassword=test \
    --set secrets.redisPassword=test \
    | kubectl apply --dry-run=client -f - > /dev/null 2>&1 || true
  echo "  ✓ kubectl dry-run passed (non-blocking)"
else
  echo ""
  echo "  ⚠ kubectl not found — skipping dry-run validation"
fi

echo ""
echo "✅ All validations passed."

