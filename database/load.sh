#!/usr/bin/env bash
#
# Rebuild the TripMate database from scratch: drop everything, create the
# schema, load the sample data, then verify the result.
#
#   ./database/load.sh
#
# Connection settings come from scripts/env.sh and can be overridden by
# exporting TM_DB_* variables first.
#
# The role and database themselves are created once by bootstrap.sql, which
# needs superuser rights and is therefore NOT run here:
#
#   sudo -u postgres psql -v ON_ERROR_STOP=1 -f database/bootstrap.sql

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/../scripts/env.sh"

DATABASE_DIR="${PROJECT_ROOT}/database"

# Run psql against the TripMate database with the configured credentials.
# PGPASSWORD is scoped to the command rather than exported globally.
tm_psql() {
    PGPASSWORD="${TM_DB_PASSWORD}" psql \
        --host="${TM_DB_HOST}" \
        --port="${TM_DB_PORT}" \
        --username="${TM_DB_USER}" \
        --dbname="${TM_DB_NAME}" \
        "$@"
}

# Fail early with a useful message rather than an authentication error.
if [[ ! -f "${DATABASE_DIR}/schema.sql" ]]; then
    echo "error: ${DATABASE_DIR}/schema.sql not found." >&2
    exit 1
fi

if ! PGPASSWORD="${TM_DB_PASSWORD}" psql \
        --host="${TM_DB_HOST}" --port="${TM_DB_PORT}" \
        --username="${TM_DB_USER}" --dbname="${TM_DB_NAME}" \
        --command='SELECT 1' >/dev/null 2>&1; then
    echo "error: cannot connect to ${TM_DB_NAME} as ${TM_DB_USER} on ${TM_DB_HOST}:${TM_DB_PORT}" >&2
    echo "       create the role and database first:" >&2
    echo "         sudo -u postgres psql -v ON_ERROR_STOP=1 -f database/bootstrap.sql" >&2
    exit 1
fi

echo "==> Dropping existing tables"
tm_psql --quiet --set=ON_ERROR_STOP=1 --file="${DATABASE_DIR}/reset.sql"

echo "==> Applying schema"
tm_psql --quiet --set=ON_ERROR_STOP=1 --file="${DATABASE_DIR}/schema.sql"

echo "==> Loading sample data"
tm_psql --quiet --set=ON_ERROR_STOP=1 --file="${DATABASE_DIR}/seed.sql" >/dev/null

echo "==> Verifying"
tm_psql --set=ON_ERROR_STOP=1 --file="${DATABASE_DIR}/verify.sql"

echo
echo "==> ${TM_DB_NAME} rebuilt and verified"
