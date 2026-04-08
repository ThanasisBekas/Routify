#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# flyway-validate.sh — Verify Flyway migration health across all services
#
# Checks performed:
#   1. Migration numbering is contiguous (no gaps, no duplicates)
#   2. Every migration file is a valid versioned SQL file (V<N>__*.sql)
#   3. File names follow snake_case convention
#   4. All services have ddl-auto: validate in their application.yml
#   5. Flyway schema config matches expected schema name
#
# Usage:
#   ./scripts/flyway-validate.sh           # run all checks
#   ./scripts/flyway-validate.sh --ci      # strict mode (non-zero exit on any warning)
#
# Created by P-28: Flyway Migration Health Verification
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

STRICT=false
if [[ "${1:-}" == "--ci" ]]; then
  STRICT=true
fi

PASS=0
WARN=0
FAIL=0

# Color codes (disabled when not a terminal)
if [[ -t 1 ]]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[0;33m'
  CYAN='\033[0;36m'
  BOLD='\033[1m'
  NC='\033[0m'
else
  RED='' GREEN='' YELLOW='' CYAN='' BOLD='' NC=''
fi

pass()  { echo -e "  ${GREEN}✓${NC} $1"; PASS=$((PASS + 1)); }
warn()  { echo -e "  ${YELLOW}⚠${NC} $1"; WARN=$((WARN + 1)); }
fail()  { echo -e "  ${RED}✗${NC} $1"; FAIL=$((FAIL + 1)); }

echo -e "${BOLD}Routify Flyway Migration Health Check${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

TOTAL_MIGRATIONS=0

# ─── Service definitions ─────────────────────────────────────────────────────
validate_service() {
  local svc_dir="$1"
  local schema_name="$2"
  local migration_dir="$PROJECT_ROOT/$svc_dir/src/main/resources/db/migration"
  local config_file="$PROJECT_ROOT/$svc_dir/src/main/resources/application.yml"

  echo -e "${CYAN}${BOLD}$svc_dir${NC} (schema: $schema_name)"

  # ── Check migration directory exists ─────────────────────────────────────
  if [[ ! -d "$migration_dir" ]]; then
    fail "Migration directory not found: $migration_dir"
    echo ""
    return
  fi

  # ── Collect migration files ──────────────────────────────────────────────
  local file_count=0
  local versions=""
  local bad_names=""

  for f in "$migration_dir"/V*.sql; do
    [[ -e "$f" ]] || continue
    file_count=$((file_count + 1))
    local basename_f
    basename_f=$(basename "$f")

    # Must match V<number>__<description>.sql (snake_case, alphanumeric + underscore)
    if echo "$basename_f" | grep -qE '^V[0-9]+__[A-Za-z0-9_]+\.sql$'; then
      local ver
      ver=$(echo "$basename_f" | sed -E 's/^V([0-9]+)__.*/\1/')
      versions="$versions $ver"
    else
      bad_names="$bad_names $basename_f"
    fi
  done

  if [[ $file_count -eq 0 ]]; then
    fail "No migration files found in $migration_dir"
    echo ""
    return
  fi

  # Report bad file names
  for bad in $bad_names; do
    fail "Invalid migration filename: $bad (expected V<N>__<snake_case>.sql)"
  done

  # ── Sort versions numerically ────────────────────────────────────────────
  local sorted_versions
  sorted_versions=$(echo "$versions" | tr ' ' '\n' | grep -v '^$' | sort -n)
  local version_count
  version_count=$(echo "$sorted_versions" | wc -l | tr -d ' ')

  local first_version
  first_version=$(echo "$sorted_versions" | head -1)
  local last_version
  last_version=$(echo "$sorted_versions" | tail -1)

  # Check starts at V1
  if [[ "$first_version" -ne 1 ]]; then
    fail "Migrations do not start at V1 (first version: V$first_version)"
  else
    pass "Starts at V1"
  fi

  # Check contiguity (no gaps)
  local expected_count=$(( last_version - first_version + 1 ))
  if [[ "$version_count" -ne "$expected_count" ]]; then
    # Find the gaps
    local i
    for (( i = first_version; i <= last_version; i++ )); do
      if ! echo "$sorted_versions" | grep -qx "$i"; then
        fail "Missing migration: V${i}"
      fi
    done
  else
    pass "Contiguous: V${first_version}–V${last_version} ($version_count migrations)"
  fi

  # Check for duplicates
  local unique_count
  unique_count=$(echo "$sorted_versions" | sort -u | wc -l | tr -d ' ')
  if [[ "$unique_count" -ne "$version_count" ]]; then
    fail "Duplicate version numbers detected"
  else
    pass "No duplicate versions"
  fi

  TOTAL_MIGRATIONS=$((TOTAL_MIGRATIONS + version_count))

  # ── Verify ddl-auto: validate in application.yml ─────────────────────────
  if [[ -f "$config_file" ]]; then
    if grep -q "ddl-auto: validate" "$config_file"; then
      pass "ddl-auto: validate is set"
    else
      fail "ddl-auto is NOT set to 'validate' in application.yml"
    fi
  else
    warn "application.yml not found at $config_file"
  fi

  # ── Verify Flyway schema matches expected ────────────────────────────────
  if [[ -f "$config_file" ]]; then
    if grep -q "schemas: $schema_name" "$config_file"; then
      pass "Flyway schema: $schema_name"
    else
      warn "Flyway schema may not match expected '$schema_name'"
    fi
  fi

  echo ""
}

# ─── Run all services ────────────────────────────────────────────────────────
validate_service "routify-route-service"      "routify"
validate_service "routify-identity-service"   "routify_identity"
validate_service "routify-audit-service"      "routify_audit"
validate_service "routify-cert-vault"         "routify_cert"

# ─── Summary ─────────────────────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${BOLD}Summary${NC}"
echo -e "  Total migrations: ${BOLD}$TOTAL_MIGRATIONS${NC} across 4 services"
echo -e "  ${GREEN}✓ $PASS passed${NC}  ${YELLOW}⚠ $WARN warnings${NC}  ${RED}✗ $FAIL failures${NC}"
echo ""

if [[ $FAIL -gt 0 ]]; then
  echo -e "${RED}${BOLD}FAILED${NC} — $FAIL check(s) failed"
  exit 1
elif [[ $WARN -gt 0 && "$STRICT" == "true" ]]; then
  echo -e "${YELLOW}${BOLD}WARNINGS${NC} — $WARN warning(s) in strict mode"
  exit 1
else
  echo -e "${GREEN}${BOLD}ALL CHECKS PASSED${NC}"
  exit 0
fi

