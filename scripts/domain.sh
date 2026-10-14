#!/usr/bin/env bash
#
# Start, stop, or inspect the GlassFish domain.
#
#   ./scripts/domain.sh start
#   ./scripts/domain.sh stop
#   ./scripts/domain.sh status

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

case "${1:-status}" in
    start)
        echo "==> Starting GlassFish domain"
        "${ASADMIN}" start-domain
        ;;
    stop)
        echo "==> Stopping GlassFish domain"
        "${ASADMIN}" stop-domain
        ;;
    status)
        "${ASADMIN}" list-domains
        ;;
    *)
        echo "usage: $(basename "$0") {start|stop|status}" >&2
        exit 2
        ;;
esac
