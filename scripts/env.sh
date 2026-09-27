#!/usr/bin/env bash
#
# Shared environment for the TripMate build and deployment scripts.
# Source this file, do not execute it.
#
#   source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
# Resolve the repository root from this script's own location so the scripts
# work regardless of the caller's working directory.
TM_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${TM_SCRIPT_DIR}/.." && pwd)"
export PROJECT_ROOT

# ---------------------------------------------------------------------------
# Java
# ---------------------------------------------------------------------------
# GlassFish 7 requires JDK 17+. JAVA_HOME is not exported by the default shell
# profile on this machine, so resolve it explicitly.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk \
        /usr/lib/jvm/default-java
    do
        if [[ -x "${candidate}/bin/java" ]]; then
            JAVA_HOME="${candidate}"
            break
        fi
    done
fi
export JAVA_HOME

# ---------------------------------------------------------------------------
# GlassFish
# ---------------------------------------------------------------------------
# Override by exporting GLASSFISH_HOME before running any script.
if [[ -z "${GLASSFISH_HOME:-}" ]]; then
    for candidate in \
        "${PROJECT_ROOT}/../tools/glassfish7" \
        "/home/srijan/Masters-III/NPMF2511ITM/CT066-3-M-EA/tools/glassfish7" \
        /opt/glassfish7 \
        "${HOME}/glassfish7"
    do
        if [[ -x "${candidate}/bin/asadmin" ]]; then
            GLASSFISH_HOME="$(cd "${candidate}" && pwd)"
            break
        fi
    done
fi
export GLASSFISH_HOME

ASADMIN="${GLASSFISH_HOME:-}/bin/asadmin"
export ASADMIN

# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------
export APP_NAME="tripmate"
export APP_VERSION="1.0.0-SNAPSHOT"
export EAR_FILE="${PROJECT_ROOT}/tripmate-ear/target/tripmate-ear-${APP_VERSION}.ear"
export APP_URL="http://localhost:8080/tripmate/"
export ADMIN_URL="http://localhost:4848"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Verify the JDK and GlassFish resolved above actually exist, and fail with an
# actionable message rather than a confusing "command not found".
tm_require_tools() {
    if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
        echo "error: no JDK found. Install JDK 17 or set JAVA_HOME." >&2
        return 1
    fi
    if [[ ! -x "${ASADMIN}" ]]; then
        echo "error: asadmin not found at '${ASADMIN}'." >&2
        echo "       Set GLASSFISH_HOME to your GlassFish 7 installation." >&2
        return 1
    fi
    return 0
}

# True when at least one GlassFish domain is running. `asadmin list-domains`
# prints "<name> running" or "<name> not running", so match the former without
# being fooled by the latter.
tm_domain_running() {
    "${ASADMIN}" list-domains 2>/dev/null \
        | grep -qE '^[[:space:]]*[^[:space:]]+[[:space:]]+running'
}
