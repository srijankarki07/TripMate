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
export APP_URL="http://localhost:8080/"
export ADMIN_URL="http://localhost:4848"

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------
# Local development credentials created by database/bootstrap.sql. Override any
# of these by exporting the variable before running a script. The JDBC resource
# and connection pool are configured on GlassFish in phase 2 and must use the
# same values.
# ---------------------------------------------------------------------------
# Local overrides
# ---------------------------------------------------------------------------
# A .env beside the repository root is read first, so anything set there wins
# over the defaults below and over nothing else. It is not committed; the
# example beside it lists what it can hold.
if [[ -f "${PROJECT_ROOT}/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "${PROJECT_ROOT}/.env"
    set +a
fi

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
export TM_APP_BASE_URL="${TM_APP_BASE_URL:-http://localhost:8080}"

# How long a mail conversation may take, in milliseconds, applied to connecting,
# waiting for a reply, and writing. JavaMail leaves all three unlimited by
# default, which means a mail server that goes quiet mid-conversation holds the
# sending thread for as long as the TCP stack allows. Ten seconds each is far
# more than a working SMTP server needs and short enough that a broken one is a
# failed send rather than a stalled server.
export TM_MAIL_TIMEOUT_MS="${TM_MAIL_TIMEOUT_MS:-10000}"

# ---------------------------------------------------------------------------
# Runtime tuning
# ---------------------------------------------------------------------------
# Applied to the domain by scripts/glassfish-tune.sh. The defaults are the ones
# docs/deployment.md arrives at for a 1 GB machine, so a deployment that does
# nothing but run the scripts still gets a server sized to its host.
#
# Every one of these is a real trade, and every one of them can be changed by
# exporting it or by putting it in .env. That is the point of having them here
# rather than only in a document.
# ---------------------------------------------------------------------------

# Heap, in megabytes. Small on purpose: the machine this defaults for has 1 GB
# of RAM, and the JVM needs roughly 250 MB on top of its heap for metaspace, the
# code cache, thread stacks and GC structures. The script sets Xms equal to Xmx
# so the heap is never resized at runtime.
export TM_JVM_HEAP_MB="${TM_JVM_HEAP_MB:-384}"

# Metaspace ceiling. PrimeFaces, Mojarra and EclipseLink together want about
# 120-150 MB, so this is a bound rather than a constraint: it turns an
# unbounded leak into a failure that names itself.
export TM_JVM_METASPACE_MB="${TM_JVM_METASPACE_MB:-192}"

# Each thread's stack. Halved from the default, which is 20-40 MB across the
# process given how many pools GlassFish starts. Safe because nothing in this
# application recurses.
export TM_JVM_THREAD_STACK_KB="${TM_JVM_THREAD_STACK_KB:-512}"

# NIO direct memory, where Grizzly puts every network buffer it hands out. It
# is outside the heap, so -Xmx does not bound it, and unbounded it is the
# reason a process can be killed for memory while the heap looks comfortable.
export TM_JVM_DIRECT_MEMORY_MB="${TM_JVM_DIRECT_MEMORY_MB:-64}"

# The request thread pool, and the setting that matters most for the load this
# application is expected to take.
#
# GlassFish 7 creates http-thread-pool at min 5, max 5. Five threads handle
# every request in the server, static resources included, and several request
# paths block for a long time: password verification is 210,000 rounds of
# PBKDF2, a trip summary waits on SMTP once per recipient, placing a trip waits
# on a reverse geocode, and a calendar sync waits on Google once per member per
# itinerary item. Five threads means five such requests stop the server for
# everybody else.
export TM_HTTP_THREADS_MIN="${TM_HTTP_THREADS_MIN:-8}"
export TM_HTTP_THREADS_MAX="${TM_HTTP_THREADS_MAX:-24}"

# How many requests may wait for a thread. The default of 4096 accepts far more
# work than the server can ever do and then lets it all time out. A smaller
# queue turns overload into a fast, visible failure instead of a slow one.
export TM_HTTP_QUEUE_SIZE="${TM_HTTP_QUEUE_SIZE:-256}"

# The JDBC pool. steady-pool-size is not a maximum: it is how many PostgreSQL
# connections are held open even when idle, and each one is a backend process
# with its own memory. max-pool-size must stay under PostgreSQL's
# max_connections, which the tuning docs set to 20; a pool allowed past that
# fails to connect rather than degrading.
export TM_DB_POOL_STEADY="${TM_DB_POOL_STEADY:-3}"
export TM_DB_POOL_MAX="${TM_DB_POOL_MAX:-16}"

# A ceiling on any single statement. Without one, a query that goes wrong holds
# its connection until the database gives up, and on a pool this small two or
# three of those is the entire pool.
export TM_DB_STATEMENT_TIMEOUT="${TM_DB_STATEMENT_TIMEOUT:-30}"

# Log rotation. GlassFish's default keeps three files of 100 MB each, which is
# 300 MB of disk on a machine whose whole database is smaller than that.
export TM_LOG_ROTATION_MB="${TM_LOG_ROTATION_MB:-10}"
export TM_LOG_HISTORY_FILES="${TM_LOG_HISTORY_FILES:-5}"

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

# ---------------------------------------------------------------------------
# Site presentation
# ---------------------------------------------------------------------------
# Whether the footer's link to the integration checks is shown, and what the
# stack line under it says. Both are deployment facts rather than application
# ones: the checks page is a development tool, and the versions depend on what
# the server was built with. Set them in .env to change them without touching
# the application.
export TM_SHOW_DIAGNOSTICS="${TM_SHOW_DIAGNOSTICS:-false}"
export TM_FOOTER_STACK="${TM_FOOTER_STACK:-Jakarta EE 10 - GlassFish 7 - PostgreSQL}"

# Whether a proxy or a tunnel is in front of this deployment and sets the
# X-Forwarded-Proto and X-Forwarded-Host headers. Off unless an operator says
# otherwise, because a request carries those headers whether or not anything
# put them there, and the application writes absolute addresses of its own (the
# calendar feed's subscribe address) from what it is told.
#
#   TM_TRUST_PROXY=true ./scripts/glassfish-setup.sh
#
# is enough; the property is read per request, so no redeploy is needed.
export TM_TRUST_PROXY="${TM_TRUST_PROXY:-false}"

# The Google Calendar connection. With no client id the feature reports itself
# unavailable rather than offering a button that would fail, and the subscribe
# address is unaffected. TM_CREDENTIALS_KEY encrypts refresh tokens at rest and
# has no default: without it nothing is stored, which is the safe direction.
export TM_GOOGLE_CLIENT_ID="${TM_GOOGLE_CLIENT_ID:-}"
export TM_GOOGLE_CLIENT_SECRET="${TM_GOOGLE_CLIENT_SECRET:-}"
export TM_CREDENTIALS_KEY="${TM_CREDENTIALS_KEY:-}"
