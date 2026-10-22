#!/usr/bin/env bash
#
# A genuine cold start, of the kind a marker would perform.
#
#   ./scripts/clean-deploy.sh
#
# Everything is discarded first: the application is undeployed, the domain is
# stopped, the seven tables are dropped, and the domain is started again. Then
# the application is deployed from a freshly built EAR and the whole test matrix
# is run against it.
#
# This is deliberately more than `deploy.sh` does. Its purpose is to prove that
# nothing works only because it was already running, which is a failure mode that
# a repeated redeploy will never reveal.
#
# It does NOT touch the database role or the database itself, the datasource, or
# the queue. Those are one-time setup, and `docs/runbook.md` section 2 covers
# them. What this tests is that the application can be assembled and run from
# nothing but the source tree and an empty schema.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Undeploying and stopping the domain"
"${ASADMIN}" undeploy "${APP_NAME}" >/dev/null 2>&1 || true
"${ASADMIN}" stop-domain >/dev/null 2>&1 || true

echo "==> Dropping every table"
if ! "${SCRIPT_DIR}/../database/load.sh" > /tmp/clean-deploy-load.log 2>&1; then
    # load.sh rebuilds and verifies; a failure here is worth surfacing rather
    # than hiding behind the deploy that follows.
    echo "error: rebuilding the database failed" >&2
    tail -20 /tmp/clean-deploy-load.log >&2
    exit 1
fi
echo "    seven tables rebuilt and verified"

echo "==> Building from source"
cd "${PROJECT_ROOT}"
mvn -B clean package > /tmp/clean-deploy-build.log 2>&1 \
    || { echo "error: the build failed" >&2; tail -20 /tmp/clean-deploy-build.log >&2; exit 1; }
echo "    $(basename "${EAR_FILE}")"

echo "==> Starting the domain"
"${ASADMIN}" start-domain >/dev/null

echo "==> Ensuring the datasource and queue exist"
"${SCRIPT_DIR}/glassfish-setup.sh" > /dev/null
"${SCRIPT_DIR}/glassfish-jms.sh" > /dev/null

echo "==> Deploying"
# --name is not optional here. Without it the container derives the application
# name from the artifact, so the EAR deploys as tripmate-ear-1.0.0-SNAPSHOT and
# undeploy.sh, which looks for tripmate, cannot find it afterwards.
"${ASADMIN}" deploy --name="${APP_NAME}" "${EAR_FILE}" > /dev/null

echo "==> Waiting for the application to answer"
for attempt in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "${APP_URL}" || true)
    if [[ "${code}" == "200" ]]; then
        echo "    answered on attempt ${attempt}"
        break
    fi
    sleep 1
    if [[ "${attempt}" == "30" ]]; then
        echo "error: the application never answered" >&2
        exit 1
    fi
done

echo "==> Running the test matrix against the cold deployment"
"${SCRIPT_DIR}/test-matrix.sh"

echo
echo "==> Cold deploy complete. ${APP_URL}"
