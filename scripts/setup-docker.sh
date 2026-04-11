#!/usr/bin/env zsh
# ─────────────────────────────────────────────────────────────────────────────
# setup-docker.sh — Import an .env file and start Docker services
#
# Accepts a .env file (downloaded from the "Generate .env" GitHub Actions
# workflow artifact) and copies it to the project root, then brings up the
# requested Docker Compose stack.
#
# If no --env-file is given, the script prompts interactively for the path.
# If a valid .env already exists at the project root, you can skip import
# with --use-existing.
#
# Modes:
#   infra   (default) — infrastructure only  (postgres, redis, kafka, rabbitmq,
#                        prometheus, grafana)
#   app               — infra + all application services
#   full              — alias for app
#
# Usage:
#   ./scripts/setup-docker.sh                                    # interactive prompt for .env path, infra only
#   ./scripts/setup-docker.sh --env-file ~/Downloads/.env        # import the given .env, infra only
#   ./scripts/setup-docker.sh --env-file ~/Downloads/.env --app  # import + start full stack
#   ./scripts/setup-docker.sh --use-existing                     # skip import, use current .env
#   ./scripts/setup-docker.sh --use-existing --app               # use current .env, full stack
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
divider() { echo -e "${YELLOW}──────────────────────────────────────────────────${NC}"; }

# ── Flags ─────────────────────────────────────────────────────────────────────
ENV_FILE_PATH=""
MODE="infra"   # infra | app
USE_EXISTING=false

for arg in "$@"; do
  case $arg in
    --env-file=*)   ENV_FILE_PATH="${arg#--env-file=}" ;;
    --app|--full)   MODE="app" ;;
    --infra-only)   MODE="infra" ;;
    --use-existing) USE_EXISTING=true ;;
    --help|-h)
      sed -n '/^# Usage:/,/^# ─/p' "$0" | sed 's/^# \{0,3\}//'
      exit 0
      ;;
    --env-file)
      error "--env-file requires a value. Use --env-file=<path> (e.g. --env-file=~/Downloads/.env)"
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

# ── Import or verify .env ─────────────────────────────────────────────────────
if [[ "$USE_EXISTING" == true ]]; then
  # ── Use the existing .env in the project root ──────────────────────────────
  if [[ ! -f "${PROJECT_DIR}/.env" ]]; then
    error "No .env file found at ${PROJECT_DIR}/.env"
    error "Either provide one with --env-file=<path> or run the 'Generate .env' GitHub Actions workflow first."
    exit 1
  fi
  info "Using existing .env at project root."
else
  # ── Interactive prompt when --env-file not supplied ────────────────────────
  if [[ -z "$ENV_FILE_PATH" ]]; then
    divider
    echo -e "${BOLD}Provide the path to your .env file${NC}"
    echo -e "  (download it from the ${CYAN}Generate .env${NC} GitHub Actions workflow artifact)"
    divider
    printf "Path to .env file: "
    read -r ENV_FILE_PATH
  fi

  # Expand ~ if present
  ENV_FILE_PATH="${ENV_FILE_PATH/#\~/$HOME}"

  if [[ -z "$ENV_FILE_PATH" ]]; then
    error "No path provided."
    exit 1
  fi

  if [[ ! -f "$ENV_FILE_PATH" ]]; then
    error "File not found: ${ENV_FILE_PATH}"
    exit 1
  fi

  divider
  info "Source  : ${ENV_FILE_PATH}"
  info "Target  : ${PROJECT_DIR}/.env"
  info "Mode    : ${MODE}"
  divider

  if [[ -f "${PROJECT_DIR}/.env" ]]; then
    warn "Existing .env will be replaced."
  fi

  cp "${ENV_FILE_PATH}" "${PROJECT_DIR}/.env"
  success "Copied ${ENV_FILE_PATH} → .env"
fi


# ── Verify the env file has the required keys ─────────────────────────────────
typeset -a REQUIRED_KEYS MISSING
REQUIRED_KEYS=(DB_PASS RABBITMQ_PASS JWT_PRIVATE_KEY JWT_PUBLIC_KEY CERT_VAULT_ENCRYPTION_KEY)
MISSING=()
for key in "${REQUIRED_KEYS[@]}"; do
  grep -q "^${key}=" "${PROJECT_DIR}/.env" || MISSING+=("$key")
done

if (( ${#MISSING[@]} > 0 )); then
  warn "The following required keys are missing from .env:"
  for key in "${MISSING[@]}"; do
    warn "  • ${key}"
  done
  warn "Re-run the 'Generate .env' GitHub Actions workflow to regenerate a complete env file."
fi

# ── Bring up Docker Compose ───────────────────────────────────────────────────
divider

if [[ "$MODE" == "infra" ]]; then
  info "Starting infrastructure services (postgres, redis, kafka, rabbitmq, prometheus, grafana)..."
  docker compose --env-file .env -f docker-compose.yml up -d
  success "Infrastructure is up. 🚀"
  echo ""
  info "To also start application services, re-run with --app:"
  echo "  ./scripts/setup-docker.sh --use-existing --app"
else
  info "Starting full stack (infra + all application services)..."
  info "Building application images (this may take a few minutes on first run)..."
  docker compose --env-file .env \
    -f docker-compose.yml \
    -f docker-compose.app.yml \
    up -d --build
  success "Full stack is up. 🚀"
fi

# ── Sync credentials to running containers ────────────────────────────────────
# When the .env is replaced (e.g. after re-running the workflow for a new branch)
# the containers keep the old credentials. Sync them now so services can connect.
divider
info "Syncing credentials to running containers..."

_DB_PASS="$(grep '^DB_PASS=' "${PROJECT_DIR}/.env" | cut -d= -f2)"
_RABBITMQ_PASS="$(grep '^RABBITMQ_PASS=' "${PROJECT_DIR}/.env" | cut -d= -f2)"

# PostgreSQL — wait for readiness then update password
if docker ps --filter "name=routify-postgres" --filter "status=running" --format "{{.Names}}" | grep -q routify-postgres; then
  for i in 1 2 3 4 5; do
    if docker exec routify-postgres pg_isready -U routify -q 2>/dev/null; then
      break
    fi
    info "Waiting for PostgreSQL to be ready (attempt ${i}/5)..."
    sleep 3
  done
  if docker exec routify-postgres psql -U routify -c "ALTER USER routify WITH PASSWORD '${_DB_PASS}';" > /dev/null 2>&1; then
    success "PostgreSQL password synced."
  else
    warn "Could not sync PostgreSQL password — service may fail to connect."
  fi
else
  info "PostgreSQL container not running — password will be set on next start."
fi

# RabbitMQ — update password via rabbitmqctl
if docker ps --filter "name=routify-rabbitmq" --filter "status=running" --format "{{.Names}}" | grep -q routify-rabbitmq; then
  for i in 1 2 3 4 5; do
    if docker exec routify-rabbitmq rabbitmq-diagnostics -q ping 2>/dev/null; then
      break
    fi
    info "Waiting for RabbitMQ to be ready (attempt ${i}/5)..."
    sleep 3
  done
  if docker exec routify-rabbitmq rabbitmqctl change_password routify "${_RABBITMQ_PASS}" > /dev/null 2>&1; then
    success "RabbitMQ password synced."
  else
    warn "Could not sync RabbitMQ password — service may fail to connect."
  fi
else
  info "RabbitMQ container not running — password will be set on next start."
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
printf "  ${CYAN}%-28s${NC} %s\n" "RabbitMQ Management"   "http://localhost:15672 (routify / ${_RABBITMQ_PASS})"
printf "  ${CYAN}%-28s${NC} %s\n" "Prometheus"            "http://localhost:9091  (admin / admin)"
printf "  ${CYAN}%-28s${NC} %s\n" "Grafana"               "http://localhost:3001  (admin / admin)"
divider
success "Done! Using .env at project root."

