#!/bin/sh
# A database per service that owns state, on one server.
#
# user-service keeps drenix_identity (created by the image from POSTGRES_DB).
# notification-service and performance-service get one each, with an owner
# each: no service's credentials can read another's tables, which is the one
# thing microservices are supposed not to share.
#
# Runs once, when the data volume is first created. An existing installation
# needs the same statements applied by hand — the README says how.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
  CREATE ROLE notification_app LOGIN PASSWORD '${NOTIFICATION_DB_PASSWORD}';
  CREATE DATABASE drenix_notification OWNER notification_app;

  CREATE ROLE performance_app LOGIN PASSWORD '${PERFORMANCE_DB_PASSWORD}';
  CREATE DATABASE drenix_performance OWNER performance_app;
EOSQL
