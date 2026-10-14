#!/usr/bin/env bash
#
# Build the TripMate EAR. Any extra arguments are passed through to Maven.
#
#   ./scripts/build.sh
#   ./scripts/build.sh -DskipTests

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

cd "${PROJECT_ROOT}"

echo "==> Building TripMate"
mvn -B clean package "$@"

echo
echo "==> Artifact: ${EAR_FILE}"
