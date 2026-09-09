#!/usr/bin/env bash
#
# Back up all three databases.
#
#   ./scripts/backup-db.sh                      write to ./backups
#   BACKUP_DIR=/srv/backups ./scripts/backup-db.sh
#
# Run it from cron nightly, and by hand before any deployment that carries a migration which
# drops or renames anything. A rollback restores code; only this restores data.
#
# One Postgres server holds three databases with a different owner each, so this dumps all three:
#
#   drenix_identity       users, roles, permissions, password hashes, the audit log
#   drenix_performance    the daily activity snapshots
#   drenix_notification   the notification inbox
#
# The dumps contain every user row and every password hash. They are as sensitive as the database
# itself: keep them off the public filesystem, and copy them off this machine — a backup that
# only exists on the server it protects is not a backup.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
CONTAINER="${PG_CONTAINER:-drenix-backend-postgres-identity-1}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mFAILED\033[0m %s\n' "$*" >&2; exit 1; }

docker inspect "$CONTAINER" >/dev/null 2>&1 || die "container $CONTAINER is not running"

# 0700: the dumps hold password hashes. Nobody but the owner reads this directory.
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# Each database has its own owner role, and each owner's password is in .env. Reading it here
# keeps credentials out of this file and out of the process list — the password reaches psql
# through the container's environment, never as an argument.
set -a; . ./.env; set +a

dump() {  # dump <database> <role> <password>
    local db="$1" role="$2" pass="$3"
    local out="$BACKUP_DIR/${db}-${STAMP}.sql.gz"

    log "Dumping $db"
    # --clean --if-exists so the restore replaces rather than collides. Custom format would allow
    # selective restore, but plain SQL is readable, and being able to grep a backup at 3am is
    # worth more here than restoring one table out of forty.
    docker exec -e PGPASSWORD="$pass" "$CONTAINER" \
        pg_dump -U "$role" -d "$db" --clean --if-exists --no-owner \
        | gzip -9 > "$out" || die "pg_dump failed for $db"

    chmod 600 "$out"

    # A dump that fails halfway leaves a valid gzip of half a database. Postgres writes this
    # marker last, so its absence means the dump is incomplete.
    if ! gzip -dc "$out" | tail -5 | grep -q 'PostgreSQL database dump complete'; then
        rm -f "$out"
        die "$db produced an incomplete dump — it has been deleted rather than kept"
    fi
    log "  $(du -h "$out" | cut -f1)  $(basename "$out")"
}

dump drenix_identity     identity_app     "${IDENTITY_DB_PASSWORD:?set IDENTITY_DB_PASSWORD}"
dump drenix_performance  performance_app  "${PERFORMANCE_DB_PASSWORD:?set PERFORMANCE_DB_PASSWORD}"
dump drenix_notification notification_app "${NOTIFICATION_DB_PASSWORD:?set NOTIFICATION_DB_PASSWORD}"

# Retention. Deleted only after the new dumps above have been verified, so a failing backup never
# removes the last good one.
log "Removing dumps older than ${RETENTION_DAYS} days"
find "$BACKUP_DIR" -name '*.sql.gz' -type f -mtime "+${RETENTION_DAYS}" -print -delete || true

log "Done. $(ls -1 "$BACKUP_DIR"/*.sql.gz 2>/dev/null | wc -l) dumps in $BACKUP_DIR"
echo
echo "Copy these off the server. To restore one:"
echo "  gzip -dc backups/drenix_identity-<stamp>.sql.gz \\"
echo "    | docker exec -i -e PGPASSWORD=\"\$IDENTITY_DB_PASSWORD\" $CONTAINER \\"
echo "        psql -U identity_app -d drenix_identity"
