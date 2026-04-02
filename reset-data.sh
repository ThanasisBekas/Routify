#!/usr/bin/env zsh
# ─────────────────────────────────────────────────────────────────────────────
# reset-data.sh — Wipe all persistent data for Routify infrastructure services
#
# What it clears:
#   • PostgreSQL  (postgres_data)
#   • Redis       (redis_data)
#   • Kafka       (kafka_data)
#   • RabbitMQ    (rabbitmq_data)
#   • Grafana     (grafana_data)   [optional, skipped with --skip-grafana]
#
# Usage:
#   ./reset-data.sh                  # wipe everything and restart infra
#   ./reset-data.sh --no-restart     # wipe everything, leave containers down
#   ./reset-data.sh --skip-grafana   # keep Grafana dashboards/settings
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

COMPOSE_FILE="docker-compose.yml"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Flags ────────────────────────────────────────────────────────────────────
RESTART=true
SKIP_GRAFANA=false

for arg in "$@"; do
  case $arg in
    --no-restart)   RESTART=false ;;
    --skip-grafana) SKIP_GRAFANA=true ;;
    --help|-h)
      sed -n '/^# Usage:/,/^# ─/p' "$0" | sed 's/^# \{0,3\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg"
      echo "Usage: $0 [--no-restart] [--skip-grafana]"
      exit 1
      ;;
  esac
done

# ── Helpers ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
divider() { echo -e "${YELLOW}──────────────────────────────────────────────────${NC}"; }

cd "$PROJECT_DIR"

divider
warn "⚠️  This will PERMANENTLY DELETE all data in:"
warn "   • PostgreSQL, Redis, Kafka, RabbitMQ$(${SKIP_GRAFANA} && echo '' || echo ', Grafana')"
warn "   Project: $PROJECT_DIR"
divider
read -r "CONFIRM?Are you sure? Type 'yes' to continue: "
if [[ "$CONFIRM" != "yes" ]]; then
  info "Aborted."
  exit 0
fi

# ── Step 1 — Stop all running containers ─────────────────────────────────────
divider
info "Stopping all containers (infra + app if running)..."
docker compose -f docker-compose.yml -f docker-compose.app.yml down 2>/dev/null \
  || docker compose -f docker-compose.yml down 2>/dev/null \
  || true
success "Containers stopped."

# ── Step 2 — Remove named volumes ────────────────────────────────────────────
divider
info "Removing volumes..."

VOLUMES=(
  "routify_postgres_data"
  "routify_redis_data"
  "routify_kafka_data"
  "routify_rabbitmq_data"
)

if [[ "$SKIP_GRAFANA" == false ]]; then
  VOLUMES+=("routify_grafana_data")
fi

for vol in "${VOLUMES[@]}"; do
  if docker volume inspect "$vol" &>/dev/null; then
    docker volume rm "$vol"
    success "Removed volume: $vol"
  else
    warn "Volume not found (already gone?): $vol"
  fi
done

# ── Step 3 — Optionally restart infra ────────────────────────────────────────
divider
if [[ "$RESTART" == true ]]; then
  info "Starting infrastructure services..."
  docker compose --env-file .env -f docker-compose.yml up -d
  success "Infrastructure is up with fresh data. 🚀"
else
  info "Skipping restart (--no-restart). Run manually:"
  echo "  docker compose --env-file .env -f docker-compose.yml up -d"
fi

divider
success "Done! All selected data volumes have been wiped."

