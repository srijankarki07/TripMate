#!/usr/bin/env bash
#
# Create the GlassFish JDBC connection pool and JDBC resource that the
# TripMate persistence unit looks up as jdbc/tripmate.
#
#   ./scripts/glassfish-setup.sh
#
# Idempotent and convergent: re-running it leaves the pool properties matching
# whatever scripts/env.sh currently says, so changing TM_DB_* and re-running is
# enough to repoint the application at a different database.
#
# deploy.sh calls this automatically before deploying, because a missing
# datasource makes the deployment itself fail.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

if ! tm_domain_running; then
    echo "==> Starting GlassFish domain"
    "${ASADMIN}" start-domain
fi

# ---------------------------------------------------------------------------
# The PostgreSQL JDBC driver must be on the server's classpath
# ---------------------------------------------------------------------------
# GlassFish does not ship a PostgreSQL driver, and the driver belongs in the
# container rather than inside the EAR — a JDBC driver loaded by a web
# application's classloader cannot be shared and breaks connection pooling.
DRIVER_DIR="${GLASSFISH_HOME}/glassfish/domains/${TM_DOMAIN}/lib"

driver_jar="$(compgen -G "${DRIVER_DIR}/postgresql-*.jar" | head -1 || true)"
if [[ -z "${driver_jar}" ]]; then
    echo "error: no postgresql-*.jar found in" >&2
    echo "         ${DRIVER_DIR}" >&2
    echo "       GlassFish cannot load the driver without it. Install one with:" >&2
    echo "         mvn dependency:get -Dartifact=org.postgresql:postgresql:42.7.4" >&2
    echo "         cp ~/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar \\" >&2
    echo "            ${DRIVER_DIR}/" >&2
    exit 1
fi
echo "==> JDBC driver: $(basename "${driver_jar}")"

# ---------------------------------------------------------------------------
# Connection pool
# ---------------------------------------------------------------------------
pool_properties="user=${TM_DB_USER}:password=${TM_DB_PASSWORD}:serverName=${TM_DB_HOST}:portNumber=${TM_DB_PORT}:databaseName=${TM_DB_NAME}"

if "${ASADMIN}" list-jdbc-connection-pools 2>/dev/null | grep -qx "${TM_POOL_NAME}"; then
    echo "==> Pool '${TM_POOL_NAME}' exists; converging its properties"
    # `set` writes each property whether or not it already existed, so the pool
    # ends up matching env.sh rather than whatever it was created with.
    while IFS='=' read -r key value; do
        [[ -z "${key}" ]] && continue
        "${ASADMIN}" set "resources.jdbc-connection-pool.${TM_POOL_NAME}.property.${key}=${value}" >/dev/null
    done < <(tr ':' '\n' <<< "${pool_properties}")
else
    echo "==> Creating pool '${TM_POOL_NAME}'"
    # Connection validation, so a connection that died while idle (database
    # restarted, network blipped) is discarded rather than handed to a
    # transaction. `meta-data` probes the connection with a JDBC metadata call,
    # which is the portable option that needs no validation table; the
    # validateatmostonceperiod keeps that probe from running on every checkout.
    "${ASADMIN}" create-jdbc-connection-pool \
        --datasourceclassname org.postgresql.ds.PGSimpleDataSource \
        --restype javax.sql.DataSource \
        --isconnectvalidatereq=true \
        --validationmethod=meta-data \
        --validateatmostonceperiod=60 \
        --property "${pool_properties}" \
        "${TM_POOL_NAME}" >/dev/null
fi

# ---------------------------------------------------------------------------
# JDBC resource
# ---------------------------------------------------------------------------
if "${ASADMIN}" list-jdbc-resources 2>/dev/null | grep -qx "${TM_JNDI_NAME}"; then
    echo "==> Resource '${TM_JNDI_NAME}' exists"
else
    echo "==> Creating resource '${TM_JNDI_NAME}'"
    "${ASADMIN}" create-jdbc-resource \
        --connectionpoolid "${TM_POOL_NAME}" \
        "${TM_JNDI_NAME}" >/dev/null
fi

# ---------------------------------------------------------------------------
# Prove the credentials and the database actually work
# ---------------------------------------------------------------------------
# Without this the first sign of a wrong password or a missing database would be
# a failed deployment with a much less obvious message.
echo "==> Pinging the pool"
"${ASADMIN}" ping-connection-pool "${TM_POOL_NAME}"

echo
echo "==> Datasource ready: ${TM_JNDI_NAME} -> ${TM_DB_URL}"
