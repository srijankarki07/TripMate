#!/usr/bin/env bash
#
# Remove the TripMate application from the running domain.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

if ! tm_domain_running; then
    echo "error: no GlassFish domain is running." >&2
    exit 1
fi

echo "==> Undeploying '${APP_NAME}'"
"${ASADMIN}" undeploy "${APP_NAME}"
