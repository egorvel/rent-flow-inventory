#!/bin/sh

set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${INVENTORY_DB_USER:?INVENTORY_DB_USER is required}"
: "${INVENTORY_DB_PASSWORD:?INVENTORY_DB_PASSWORD is required}"

psql \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 \
    --set=inventory_user="$INVENTORY_DB_USER" \
    --set=inventory_password="$INVENTORY_DB_PASSWORD" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'inventory_user',
    :'inventory_password'
)
WHERE NOT EXISTS (
    SELECT
    FROM pg_catalog.pg_roles
    WHERE rolname = :'inventory_user'
)
\gexec

SELECT format(
    'CREATE SCHEMA inventory AUTHORIZATION %I',
    :'inventory_user'
)
WHERE NOT EXISTS (
    SELECT
    FROM pg_catalog.pg_namespace
    WHERE nspname = 'inventory'
)
\gexec
SQL
