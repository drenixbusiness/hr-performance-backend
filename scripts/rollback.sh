#!/usr/bin/env bash
#
# Put the previous release back.
#
#   ./scripts/rollback.sh
#
# deploy.sh tags the running images :rollback before it starts the new ones, and rolls back by
# itself when the health check fails. This script is for the other case: the deployment succeeded,
# the gateway answered /api/health, and the release turned out to be wrong anyway.
#
# IT DOES NOT TOUCH THE DATABASE. Flyway migrations that have committed stay committed. An
# additive migration — a new nullable column, a new table — is harmless to leave in place while
# older code runs, and that is the shape of every migration in this repository so far. A
# destructive one is not, and no script can guess which you have: restore from a backup instead.
# scripts/backup-db.sh, and the restore command in docs/DEPLOYMENT.md.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILES=(-f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml)
SERVICES=(user-service auth-service performance-service notification-service edge-gateway)
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8443/api/health}"
LOCK_FILE="${LOCK_FILE:-/tmp/drenix-deploy.lock}"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mFAILED\033[0m %s\n' "$*" >&2; exit 1; }
compose() { docker compose --env-file .env "${COMPOSE_FILES[@]}" "$@"; }

# The same lock deploy.sh takes. Rolling back while a deployment is mid-flight would have the two
# fighting over the same tags.
exec 9>"$LOCK_FILE"
flock -w 300 9 || die "a deployment is running (lock: $LOCK_FILE)"

missing=()
for svc in "${SERVICES[@]}"; do
    docker image inspect "drenix-backend-${svc}:rollback" >/dev/null 2>&1 || missing+=("$svc")
done
if (( ${#missing[@]} )); then
    die "no :rollback image for: ${missing[*]}
This happens when nothing has been deployed since the images were last pruned, or on a first
deployment. Check out the previous commit and run ./scripts/deploy.sh instead."
fi

log "Restoring the previous images"
for svc in "${SERVICES[@]}"; do
    docker tag "drenix-backend-${svc}:rollback" "drenix-backend-${svc}:latest"
done

compose up -d || die "compose up failed during rollback — the service is down, read the logs"

log "Waiting for the gateway"
for _ in $(seq 1 48); do
    if curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then
        log "Healthy. Rolled back."
        compose ps
        exit 0
    fi
    sleep 5
done

compose logs --tail=60 edge-gateway >&2 || true
die "the previous release did not come up either. The database schema may be ahead of this code:
if the last deployment ran a migration that removed or renamed something, restore from a backup."
