#!/usr/bin/env bash
#
# See a change to a page, a stylesheet or a script without rebuilding.
#
#   ./scripts/live.sh            copy everything newer than the deployment, once
#   ./scripts/live.sh --watch    keep watching, and copy each save as it happens
#
# A full deploy is only needed for Java. Use ./scripts/deploy.sh for that.
#
# This writes into GlassFish's own copy of the application, which the next
# deploy replaces. It is temporary by design and it changes nothing in git.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="${PROJECT_ROOT}/tripmate-web/src/main/webapp"
# The exploded directory is named after the deployment, which `deploy.sh` sets
# with --name, and not after the context root. It stayed `tripmate` when the
# application moved to being served at the root of the host.
DEPLOYED="${GLASSFISH_HOME}/glassfish/domains/${TM_DOMAIN}/applications/${APP_NAME}/tripmate-web_war"

if [[ ! -d "${DEPLOYED}" ]]; then
    echo "error: ${DEPLOYED} does not exist." >&2
    echo "       Deploy once first: ./scripts/deploy.sh -DskipTests" >&2
    exit 1
fi

exec node "${SCRIPT_DIR}/live.mjs" "${SOURCE}" "${DEPLOYED}" "$@"
