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

# The persistence unit resolves jdbc/tripmate when the application deploys, so
# the datasource has to exist first — a missing one fails the deployment rather
# than failing later with a clear message. glassfish-setup.sh is idempotent, and
# it starts the domain if it is not already up.
echo
"$(dirname "${BASH_SOURCE[0]}")/glassfish-setup.sh"

# Same reasoning for the queue: ExpenseMessageBean binds to it during
# deployment.
echo
"$(dirname "${BASH_SOURCE[0]}")/glassfish-jms.sh"

echo
echo "==> Deploying '${APP_NAME}'"
"${ASADMIN}" deploy --force=true --name="${APP_NAME}" "${EAR_FILE}"

echo
echo "==> Deployed. Open ${APP_URL}"
