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

# The lifecycle resources come next, because deployment fails without them and
# the failure names the missing resource rather than the reason: the persistence
# unit resolves jdbc/tripmate as it deploys, ExpenseMessageBean binds to the
# queue, and MailService injects the mail session.
echo
"$(dirname "${BASH_SOURCE[0]}")/glassfish-jms.sh"

echo
"$(dirname "${BASH_SOURCE[0]}")/glassfish-mail.sh"

# Finally the sizes. The thread pool, the JDBC pool, the JVM options and the log
# rotation all have defaults that are wrong for a small machine, and the request
# thread pool in particular defaults to five threads, which is the number that
# decides how many people the application can serve at once. These are applied
# to any domain the scripts touch, so a deployment that never reads
# docs/deployment.md still gets a server sized for its host.
#
# It runs after glassfish-setup.sh because that is what creates the JDBC pool
# whose size is being set here.
echo
"$(dirname "${BASH_SOURCE[0]}")/glassfish-tune.sh"

echo
echo "==> Deploying '${APP_NAME}'"
"${ASADMIN}" deploy --force=true --name="${APP_NAME}" "${EAR_FILE}"

echo
echo "==> Deployed. Open ${APP_URL}"
