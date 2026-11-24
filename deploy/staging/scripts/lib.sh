# Shared helpers for the staging scripts (sourced, not run).
set -euo pipefail
STAGING_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$(cd "$STAGING_DIR/../.." && pwd)"
MVN="${MVN:-/d/maven/apache-maven-3.9.16/bin/mvn}"
cd "$STAGING_DIR"
[[ -f .env ]] || { echo "No .env: run scripts/init-env.sh first." >&2; exit 1; }
set -a; . ./.env; set +a
compose() { docker compose "$@"; }

wait_healthy() {  # wait_healthy <service> <timeout-seconds>
  local svc="$1" limit="${2:-300}" id status
  for ((i = 0; i < limit; i += 3)); do
    id="$(compose ps -q "$svc" 2>/dev/null || true)"
    if [[ -n "$id" ]]; then
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
      [[ "$status" == healthy || "$status" == running && "$svc" != api ]] && return 0
      [[ "$status" == exited || "$status" == dead ]] && { echo "$svc $status" >&2; compose logs --tail 80 "$svc" >&2; return 1; }
    fi
    sleep 3
  done
  echo "$svc not healthy after ${limit}s" >&2; compose logs --tail 80 "$svc" >&2; return 1
}
