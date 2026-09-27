#!/usr/bin/env bash
#
# Build the EAR, make sure a GlassFish domain is running, and deploy to it.
# Redeployment is forced, so this is safe to run repeatedly.
#
#   ./scripts/deploy.sh [maven args...]

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

cd "${PROJECT_ROOT}"

echo "==> Building"
mvn -B clean package "$@"

if ! tm_domain_running; then
    echo
    echo "==> Starting GlassFish domain"
    "${ASADMIN}" start-domain
fi

echo
echo "==> Deploying '${APP_NAME}'"
"${ASADMIN}" deploy --force=true --name="${APP_NAME}" "${EAR_FILE}"

echo
echo "==> Deployed. Open ${APP_URL}"
