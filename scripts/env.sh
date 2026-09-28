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
# Database
# ---------------------------------------------------------------------------
# Local development credentials created by database/bootstrap.sql. Override any
# of these by exporting the variable before running a script. The JDBC resource
# and connection pool are configured on GlassFish in phase 2 and must use the
# same values.
export TM_DB_NAME="${TM_DB_NAME:-tripmate}"
export TM_DB_USER="${TM_DB_USER:-tripmate}"
export TM_DB_PASSWORD="${TM_DB_PASSWORD:-tripmate_dev_pw}"
export TM_DB_HOST="${TM_DB_HOST:-localhost}"
export TM_DB_PORT="${TM_DB_PORT:-5432}"
export TM_DB_URL="jdbc:postgresql://${TM_DB_HOST}:${TM_DB_PORT}/${TM_DB_NAME}"

# GlassFish JDBC resource names. scripts/glassfish-setup.sh creates these, and
# tripmate-ejb/src/main/resources/META-INF/persistence.xml refers to the JNDI
# name, so changing it here means changing it there too.
export TM_DOMAIN="${TM_DOMAIN:-domain1}"
export TM_POOL_NAME="${TM_POOL_NAME:-tripmatePool}"
export TM_JNDI_NAME="${TM_JNDI_NAME:-jdbc/tripmate}"

# ---------------------------------------------------------------------------
# Messaging
# ---------------------------------------------------------------------------
# Created by scripts/glassfish-jms.sh. The queue name is looked up by
# ExpenseService when it sends and by ExpenseMessageBean's activation config, so
# changing it here means changing it in both of those too.
export TM_JMS_QUEUE="${TM_JMS_QUEUE:-jms/ExpenseQueue}"
export TM_JMS_FACTORY="${TM_JMS_FACTORY:-jms/ExpenseQueueFactory}"
export TM_JMS_PHYSICAL="${TM_JMS_PHYSICAL:-jms_ExpenseQueue}"

# ---------------------------------------------------------------------------
# Email
# ---------------------------------------------------------------------------
# Created by scripts/glassfish-mail.sh. The JNDI name is looked up by
# MailService, so changing it here means changing it there too.
#
# TM_MAIL_HOST is the one setting that decides whether mail is actually sent.
# Left empty, which is the default, the JavaMail resource is still created so
# the application deploys, but MailService renders each message and writes it to
# the server log instead of handing it to an SMTP server. That is deliberate: a
# fresh checkout with no credentials should run and be demonstrable, and the
# alternative is either a deployment that fails or mail that silently vanishes.
#
# To send for real, export these before deploying:
#
#   TM_MAIL_HOST=smtp.example.com
#   TM_MAIL_PORT=587
#   TM_MAIL_USER=apikey
#   TM_MAIL_PASSWORD=...
#   TM_MAIL_FROM="TripMate <no-reply@example.com>"
#
# TM_APP_BASE_URL is what links in the emails are built from. It cannot be
# derived at send time: mail is composed outside a request, so there is no
# incoming request to read a host from.
export TM_MAIL_JNDI="${TM_MAIL_JNDI:-mail/TripMate}"
export TM_MAIL_HOST="${TM_MAIL_HOST:-}"
export TM_MAIL_PORT="${TM_MAIL_PORT:-587}"
export TM_MAIL_USER="${TM_MAIL_USER:-}"
export TM_MAIL_PASSWORD="${TM_MAIL_PASSWORD:-}"
export TM_MAIL_FROM="${TM_MAIL_FROM:-TripMate <no-reply@tripmate.test>}"
export TM_MAIL_STARTTLS="${TM_MAIL_STARTTLS:-true}"
export TM_APP_BASE_URL="${TM_APP_BASE_URL:-http://localhost:8080/tripmate}"

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
