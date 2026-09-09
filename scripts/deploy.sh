#!/usr/bin/env bash
#
# Deploy drenix-backend on the production server.
#
#   ./scripts/deploy.sh              deploy whatever is checked out
#   ./scripts/deploy.sh --no-build   restart with the images already built
#
# Run it on the server, from the repository root, as the deploy user. CI calls exactly this over
# SSH, so there is one deployment path and not two.
#
# What it guarantees:
#
#   * Only one deployment at a time. A second run blocks on a lock rather than interleaving with
#     the first and leaving half the services on each version.
#   * Images are built before anything running is touched. A compile error costs nothing.
#   * The previous images are tagged :rollback before the new ones start, so a failure has
#     somewhere to go back to.
#   * The gateway must answer /api/health before the deployment is called a success.
#   * A failed health check rolls the containers back automatically.
#   * Volumes are never removed. `down -v` does not appear in this file and must not.
#
# What it cannot guarantee: a Flyway migration that has already committed is not undone by a
# rollback. Rolling the code back leaves the schema ahead of it, which is safe for an additive
# migration and is not safe for a destructive one. Take a backup first — scripts/backup-db.sh —
# and read the note in docs/DEPLOYMENT.md before deploying anything that drops or renames.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILES=(-f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml)
SERVICES=(user-service auth-service performance-service notification-service edge-gateway)
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8443/api/health}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"
LOCK_FILE="${LOCK_FILE:-/tmp/drenix-deploy.lock}"
BUILD=1
[[ "${1:-}" == "--no-build" ]] && BUILD=0

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mFAILED\033[0m %s\n' "$*" >&2; exit 1; }

compose() { docker compose --env-file .env "${COMPOSE_FILES[@]}" "$@"; }

# --------------------------------------------------------------------------- one at a time
#
# Two deployments running at once build into the same image tags and restart each other's
# containers. flock makes the second wait; if it waits more than ten minutes something is stuck
# and it should fail loudly rather than pile up.
exec 9>"$LOCK_FILE"
flock -w 600 9 || die "another deployment is already running (lock: $LOCK_FILE)"

# --------------------------------------------------------------------------- preconditions
[[ -f .env ]] || die ".env is missing. Copy .env.example and fill it in."
[[ -d deploy/tls ]] || die "deploy/tls is missing. Run ./scripts/gen-certs.sh first."
[[ -f deploy/tls/internal-ca.crt ]] || die "deploy/tls holds no CA. Run ./scripts/gen-certs.sh."

# A missing variable would otherwise surface as a container that starts and immediately dies.
compose config >/dev/null || die "compose configuration is invalid"

# --------------------------------------------------------------------------- build
if (( BUILD )); then
    log "Building images"
    # Built before anything is stopped. A failure here leaves production untouched.
    compose build --pull || die "build failed — nothing was changed"
fi

# --------------------------------------------------------------------------- rollback point
#
# Tag whatever is running now. `up -d` will replace the :latest tags, and without this there
# would be no name left pointing at the images that were working a minute ago.
log "Tagging the running images as :rollback"
for svc in "${SERVICES[@]}"; do
    current="$(docker inspect --format '{{.Image}}' "drenix-backend-${svc}-1" 2>/dev/null || true)"
    if [[ -n "$current" ]]; then
        docker tag "$current" "drenix-backend-${svc}:rollback"
    else
        warn "$svc is not running yet — first deployment, nothing to roll back to"
    fi
done

# --------------------------------------------------------------------------- start
#
# `up -d` recreates only the containers whose image or configuration changed. Postgres and Redis
# are untouched by an application-only deployment, so the databases do not even restart.
log "Starting"
compose up -d --remove-orphans || die "compose up failed"

# --------------------------------------------------------------------------- verify
#
# The gateway answering /api/health means: the JVM started, Spring wired every bean, Flyway ran
# every migration without failing, and the mTLS handshake to the other services succeeded. A
# migration failure stops the service from starting, so it stops the deployment here.
log "Waiting for the gateway (up to ${HEALTH_TIMEOUT}s)"
deadline=$(( SECONDS + HEALTH_TIMEOUT ))
healthy=0
while (( SECONDS < deadline )); do
    if curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then
        healthy=1
        break
    fi
    sleep 5
done

if (( ! healthy )); then
    warn "The gateway never became healthy. Rolling back."
    warn "Logs from the failed deployment:"
    compose logs --tail=60 edge-gateway user-service >&2 || true

    for svc in "${SERVICES[@]}"; do
        if docker image inspect "drenix-backend-${svc}:rollback" >/dev/null 2>&1; then
            docker tag "drenix-backend-${svc}:rollback" "drenix-backend-${svc}:latest"
        fi
    done
    compose up -d || warn "the rollback itself failed — the service is down, look at the logs"
    die "deployment rolled back. The database schema was NOT rolled back; see docs/DEPLOYMENT.md."
fi

# --------------------------------------------------------------------------- done
log "Healthy. Deployed."

# Dangling images only — layers no tag points at any more. `image prune -a` would delete the
# :rollback images this script depends on, and `system prune` would take the build cache with it.
docker image prune -f >/dev/null 2>&1 || true

compose ps
