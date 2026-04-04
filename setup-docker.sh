#!/usr/bin/env zsh
# ─────────────────────────────────────────────────────────────────────────────
# setup-docker.sh — Select a branch env file and start Docker services
#
# Copies environments/.env.<branch> to the project root as .env, then brings
# up the requested Docker Compose stack.
#
# Modes:
#   infra   (default) — infrastructure only  (postgres, redis, kafka, rabbitmq,
#                        prometheus, grafana)
#   app               — infra + all application services
#   full              — alias for app
#
# Usage:
#   ./setup-docker.sh                         # interactive branch picker, infra only
#   ./setup-docker.sh --branch develop        # use develop env, infra only
#   ./setup-docker.sh --branch release/1      # use release/1 env, infra only
#   ./setup-docker.sh --branch develop --app  # infra + all app services
#   ./setup-docker.sh --branch develop --infra-only
#   ./setup-docker.sh --list                  # list available env files and exit
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENVIRONMENTS_DIR="${PROJECT_DIR}/environments"

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
divider() { echo -e "${YELLOW}──────────────────────────────────────────────────${NC}"; }

# ── Flags ─────────────────────────────────────────────────────────────────────
BRANCH=""
MODE="infra"   # infra | app
LIST_ONLY=false

for arg in "$@"; do
  case $arg in
    --branch=*)   BRANCH="${arg#--branch=}" ;;
    --app|--full) MODE="app" ;;
    --infra-only) MODE="infra" ;;
    --list|-l)    LIST_ONLY=true ;;
    --help|-h)
      sed -n '/^# Usage:/,/^# ─/p' "$0" | sed 's/^# \{0,3\}//'
      exit 0
      ;;
    --branch)
      error "--branch requires a value. Use --branch=<name> (e.g. --branch=develop)"
      exit 1
      ;;
    *)
      error "Unknown option: $arg"
      echo "Run '$0 --help' for usage."
      exit 1
      ;;
  esac
done

cd "$PROJECT_DIR"

# ── Collect available env files ───────────────────────────────────────────────
# Scan environments/ for files matching .env.<something>
typeset -a ENV_FILES BRANCHES
ENV_FILES=()
BRANCHES=()

for f in "${ENVIRONMENTS_DIR}"/.env.*; do
  [[ -f "$f" ]] || continue
  filename="$(basename "$f")"        # .env.develop
  branch="${filename#.env.}"         # develop
  ENV_FILES+=("$f")
  BRANCHES+=("$branch")
done

if [[ ${#ENV_FILES[@]} -eq 0 ]]; then
  error "No env files found in ${ENVIRONMENTS_DIR}/"
  error "Run the 'Generate .env' GitHub Actions workflow first."
  exit 1
fi

# ── --list mode ───────────────────────────────────────────────────────────────
if [[ "$LIST_ONLY" == true ]]; then
  divider
  echo -e "${BOLD}Available env files:${NC}"
  divider
  for (( i = 1; i <= ${#BRANCHES[@]}; i++ )); do
    printf "  ${CYAN}%-30s${NC} %s\n" "${BRANCHES[$i]}" "${ENV_FILES[$i]}"
  done
  divider
  exit 0
fi

# ── Interactive branch picker (when --branch not supplied) ────────────────────
if [[ -z "$BRANCH" ]]; then
  divider
  echo -e "${BOLD}Select a branch environment:${NC}"
  divider
  for (( i = 1; i <= ${#BRANCHES[@]}; i++ )); do
    printf "  ${CYAN}%2d)${NC}  %s\n" "$i" "${BRANCHES[$i]}"
  done
  divider
  printf "Enter number [1-%d]: " "${#BRANCHES[@]}"
  read -r CHOICE

  if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || \
     (( CHOICE < 1 )) || (( CHOICE > ${#BRANCHES[@]} )); then
    error "Invalid selection: '${CHOICE}'"
    exit 1
  fi

  BRANCH="${BRANCHES[$CHOICE]}"
fi

# ── Resolve the safe filename (mirrors the workflow: '/' → '-', lowercase) ───
SAFE_BRANCH="$(echo "${BRANCH}" | tr '/' '-' | tr '[:upper:]' '[:lower:]')"
ENV_FILE="${ENVIRONMENTS_DIR}/.env.${SAFE_BRANCH}"

if [[ ! -f "$ENV_FILE" ]]; then
  error "Env file not found: ${ENV_FILE}"
  error "Available files:"
  for b in "${BRANCHES[@]}"; do
    error "  • ${b}"
  done
  error "Run the 'Generate .env' GitHub Actions workflow for branch '${BRANCH}'."
  exit 1
fi

# ── Copy env file to project root ─────────────────────────────────────────────
divider
info "Branch  : ${BRANCH}"
info "Env file: ${ENV_FILE}"
info "Mode    : ${MODE}"
divider

if [[ -f "${PROJECT_DIR}/.env" ]]; then
  warn "Existing .env will be replaced."
fi

cp "${ENV_FILE}" "${PROJECT_DIR}/.env"
success "Copied ${ENV_FILE} → .env"


# ── Verify the env file has the required keys ─────────────────────────────────
typeset -a REQUIRED_KEYS MISSING
REQUIRED_KEYS=(DB_PASS RABBITMQ_PASS JWT_PRIVATE_KEY JWT_PUBLIC_KEY CERT_VAULT_ENCRYPTION_KEY)
MISSING=()
for key in "${REQUIRED_KEYS[@]}"; do
  grep -q "^${key}=" "${PROJECT_DIR}/.env" || MISSING+=("$key")
done

if (( ${#MISSING[@]} > 0 )); then
  warn "The following required keys are missing from ${ENV_FILE}:"
  for key in "${MISSING[@]}"; do
    warn "  • ${key}"
  done
  warn "Re-run the 'Generate .env' workflow to regenerate a complete env file."
fi

# ── Bring up Docker Compose ───────────────────────────────────────────────────
divider

if [[ "$MODE" == "infra" ]]; then
  info "Starting infrastructure services (postgres, redis, kafka, rabbitmq, prometheus, grafana)..."
  docker compose --env-file .env -f docker-compose.yml up -d
  success "Infrastructure is up. 🚀"
  echo ""
  info "To also start application services, re-run with --app:"
  echo "  ./setup-docker.sh --branch ${BRANCH} --app"
else
  info "Starting full stack (infra + all application services)..."
  info "Building application images (this may take a few minutes on first run)..."
  docker compose --env-file .env \
    -f docker-compose.yml \
    -f docker-compose.app.yml \
    up -d --build
  success "Full stack is up. 🚀"
fi

# ── Print service URLs ────────────────────────────────────────────────────────
divider
echo -e "${BOLD}Service endpoints:${NC}"
echo ""
printf "  ${CYAN}%-28s${NC} %s\n" "API Gateway"        "http://localhost:8080"
printf "  ${CYAN}%-28s${NC} %s\n" "Admin API (BFF)"    "http://localhost:8082"

if [[ "$MODE" == "app" ]]; then
  printf "  ${CYAN}%-28s${NC} %s\n" "Dashboard"           "http://localhost:3000"
  printf "  ${CYAN}%-28s${NC} %s\n" "Identity Service"    "http://localhost:8083"
  printf "  ${CYAN}%-28s${NC} %s\n" "Route Service"       "http://localhost:8081"
  printf "  ${CYAN}%-28s${NC} %s\n" "Audit Service"       "http://localhost:8084"
  printf "  ${CYAN}%-28s${NC} %s\n" "Cert Vault"          "http://localhost:8085"
  printf "  ${CYAN}%-28s${NC} %s\n" "AI Service"          "http://localhost:8086"
fi

echo ""
printf "  ${CYAN}%-28s${NC} %s\n" "RabbitMQ Management"  "http://localhost:15672  (routify / <RABBITMQ_PASS>)"
printf "  ${CYAN}%-28s${NC} %s\n" "Prometheus"            "http://localhost:9091"
printf "  ${CYAN}%-28s${NC} %s\n" "Grafana"               "http://localhost:3001  (admin / <GRAFANA_PASSWORD>)"
divider
success "Done! Active env: environments/.env.${SAFE_BRANCH}"

