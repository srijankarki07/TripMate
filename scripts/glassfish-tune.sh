#!/usr/bin/env bash
#
# Tune the GlassFish domain for the machine it is actually running on.
#
#   ./scripts/glassfish-tune.sh [--restart]
#
# This is the scripted form of section 4 of docs/deployment.md. It exists
# because the differences it makes are the ones that decide whether the
# application survives a second reader, and a list of asadmin commands in a
# document is a thing people skip, mistype, or apply to the wrong domain.
#
# Idempotent and convergent, like the other glassfish-*.sh scripts: re-running
# it leaves the domain matching scripts/env.sh, so changing a TM_* value and
# re-running is the way to change any of it.
#
# WHAT IT CHANGES, and why each one matters on a small host:
#
#   * the request thread pool, from GlassFish's default of five threads to
#     something that can hold more than one reader. This is the single largest
#     difference for the load the application is expected to take.
#   * the JDBC pool, sized to match PostgreSQL's max_connections, with a
#     statement timeout so one bad query cannot hold a connection forever.
#   * the JVM, bounded in every direction that can otherwise grow without
#     limit on a 1 GB machine.
#   * log rotation, so the log cannot grow to hundreds of megabytes.
#
# JVM options are read at process start, so changing them needs a domain
# restart before they take effect. The thread pool, the JDBC pool and log
# rotation are live. The script says which of the two it just did; pass
# --restart to have it restart the domain itself.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

RESTART=false
for arg in "$@"; do
    case "${arg}" in
        --restart) RESTART=true ;;
        *) echo "usage: $(basename "$0") [--restart]" >&2; exit 2 ;;
    esac
done

if ! tm_domain_running; then
    echo "==> Starting GlassFish domain"
    "${ASADMIN}" start-domain >/dev/null
fi

# Whether anything was written that only a restart can pick up.
JVM_CHANGED=false

# ---------------------------------------------------------------------------
# JVM options
# ---------------------------------------------------------------------------
# asadmin has no "set this option", only create (which fails if the option
# exists) and delete (which fails if it does not), so convergence is written as
# a delete of whatever is in the family that should not be, followed by a create
# of what should. Families are matched by prefix rather than by value so that
# changing TM_JVM_HEAP_MB from 384 to 512 replaces the old option instead of
# leaving both in domain.xml with the first one winning.

# asadmin splits its command line on ':' before the option reaches the parser,
# so a colon inside a value has to be escaped with a backslash or the text after
# it is read as the start of a second option. Without this, the obvious
# `create-jvm-options '-XX:MaxMetaspaceSize=192m'` fails with
#
#     JVM option MaxMetaspaceSize=192m is invalid because it does not start with a '-'
#
# which names the half of the option it kept and not the colon that caused it.
# The escape is a parsing artefact only: what lands in domain.xml is the plain
# colon, and `list-jvm-options` reads it back without one. Section 4 of
# docs/deployment.md shows these commands without the escape, so running them by
# hand fails exactly this way.
tm_escaped() {
    printf '%s' "${1//:/\\:}"
}

# Add an option unless it is already present. Presence is tested against the
# unescaped value, because that is the form list-jvm-options reports.
tm_add_jvm_option() {
    local opt="$1"
    if "${ASADMIN}" list-jvm-options 2>/dev/null | grep -qxF -- "${opt}"; then
        return 0
    fi
    "${ASADMIN}" create-jvm-options "$(tm_escaped "${opt}")" >/dev/null
    JVM_CHANGED=true
}

# Converge one family of options onto an exact set of wanted values.
#
# Takes a regular expression matching the family and then the values that
# family should end up holding. Anything currently present that is not wanted is
# deleted; anything wanted that is missing is created. Two values are passed for
# the heap because Xms and Xmx are chosen together and a run that converged them
# one at a time would delete the first while setting the second.
#
# What is already correct is left alone rather than re-created, so a second run
# of this script is a no-op. That matters: a needless re-create reports a change
# that did not happen, which makes the "JVM options changed, restart needed"
# line meaningless and would have the script ask for a restart on every run.
tm_set_jvm_family() {
    local pattern="$1"; shift
    local -a wanted=("$@")
    local opt want found

    while IFS= read -r opt; do
        [[ -z "${opt}" ]] && continue
        found=false
        for want in "${wanted[@]}"; do
            [[ "${opt}" == "${want}" ]] && found=true
        done
        if [[ "${found}" == "false" ]]; then
            "${ASADMIN}" delete-jvm-options "$(tm_escaped "${opt}")" >/dev/null
        fi
    done < <("${ASADMIN}" list-jvm-options 2>/dev/null \
                 | grep -E '^-' | grep -E "${pattern}" || true)

    for want in "${wanted[@]}"; do
        tm_add_jvm_option "${want}"
    done
}

echo "==> JVM options"

# Heap, and heap only. The default is 512m, which does not fit beside
# PostgreSQL on a 1 GB machine once metaspace and the code cache are counted.
# Xms is pinned equal to Xmx so the heap is not resized at runtime: growing and
# shrinking it costs a full collection each way, and on a burstable fraction of
# a core that is the worst possible time to spend CPU.
tm_set_jvm_family '^-Xm[sx]' "-Xms${TM_JVM_HEAP_MB}m" "-Xmx${TM_JVM_HEAP_MB}m"

# Metaspace is unbounded by default and is where a JSF application grows if it
# grows anywhere: PrimeFaces, Mojarra and EclipseLink together want 120-150 MB.
# A ceiling turns a slow leak into a loud OutOfMemoryError naming metaspace,
# rather than an anonymous death somewhere else.
tm_set_jvm_family '^-XX:MaxMetaspaceSize=' "-XX:MaxMetaspaceSize=${TM_JVM_METASPACE_MB}m"

# Direct memory is where Grizzly puts every network buffer, and it is outside
# the heap, so -Xmx does not bound it. Left alone it can quietly take a hundred
# megabytes, and it is the reason a process can be killed for memory while the
# heap looks comfortable.
tm_set_jvm_family '^-XX:MaxDirectMemorySize=' "-XX:MaxDirectMemorySize=${TM_JVM_DIRECT_MEMORY_MB}m"

# SerialGC rather than the default G1. G1 keeps remembered sets and runs
# concurrent marking threads that cost memory and CPU whatever the load is.
# Below roughly a gigabyte of heap SerialGC is both smaller and faster, and on
# a burstable fraction of a core the CPU it does not spend is worth as much as
# the memory.
tm_set_jvm_family '^-XX:[+-]Use(G1|Serial|Parallel)GC' "-XX:+UseSerialGC"

# Halve each thread's stack, which adds up across the pools GlassFish starts
# (request threads, EJB pool, JMS, timers) and is safe here because nothing in
# this application recurses.
tm_set_jvm_family '^-Xss' "-Xss${TM_JVM_THREAD_STACK_KB}k"

# Finally: the host's entropy pool. Session ids and CSRF tokens are drawn from
# SecureRandom, and the default /dev/random can block a request until enough
# entropy has accumulated, which shows up as a first request after a restart
# taking seconds for no visible reason. urandom is not weaker for this use; the
# blocking behaviour is only relevant where a predictable value is a disaster.
tm_add_jvm_option "-Djava.security.egd=file:/dev/./urandom"

echo "    heap ${TM_JVM_HEAP_MB}m, metaspace ${TM_JVM_METASPACE_MB}m, direct ${TM_JVM_DIRECT_MEMORY_MB}m, SerialGC"

# ---------------------------------------------------------------------------
# The request thread pool
# ---------------------------------------------------------------------------
# This is the concurrency ceiling and the reason the application feels slow
# with more than one reader.
#
# GlassFish 7 creates http-thread-pool with min 5 and max 5. Five. Every request
# in the server, static resources included, is handled by one of five threads,
# and any request path that blocks holds one of them for its whole duration.
# Several paths here do block, on things that are slow by nature or by
# accident: password verification is 210,000 rounds of PBKDF2, sending a
# summary waits on SMTP once per recipient, placing a trip waits on a reverse
# geocode, and a calendar sync waits on Google once per member per itinerary
# item. With five threads, five readers doing any of those at once is a stalled
# server for everyone else.
#
# 24 is chosen against the machine rather than against a benchmark: on one
# burstable core more threads cannot add throughput, but they do stop one slow
# request from denying service to the rest, which is the failure actually being
# reported. Raise it on a larger host.
echo
echo "==> Request thread pool"
"${ASADMIN}" set \
    "configs.config.server-config.thread-pools.thread-pool.http-thread-pool.min-thread-pool-size=${TM_HTTP_THREADS_MIN}" \
    "configs.config.server-config.thread-pools.thread-pool.http-thread-pool.max-thread-pool-size=${TM_HTTP_THREADS_MAX}" \
    "configs.config.server-config.thread-pools.thread-pool.http-thread-pool.max-queue-size=${TM_HTTP_QUEUE_SIZE}" \
    >/dev/null
echo "    ${TM_HTTP_THREADS_MIN}-${TM_HTTP_THREADS_MAX} threads, queue ${TM_HTTP_QUEUE_SIZE}"

# ---------------------------------------------------------------------------
# The JDBC pool
# ---------------------------------------------------------------------------
# steady-pool-size is the count of connections the pool keeps open whether or
# not they are in use, and each one is a PostgreSQL backend process holding its
# own memory. The default of 8 is eight of those on a machine that can spare
# about a hundred megabytes for the whole database.
#
# max-pool-size is deliberately under PostgreSQL's max_connections (20 in the
# tuning docs). A pool allowed to grow past that does not degrade gracefully:
# it starts failing to connect, which surfaces as an application error rather
# than as the database being full.
echo
echo "==> JDBC pool '${TM_POOL_NAME}'"
"${ASADMIN}" set \
    "resources.jdbc-connection-pool.${TM_POOL_NAME}.steady-pool-size=${TM_DB_POOL_STEADY}" \
    "resources.jdbc-connection-pool.${TM_POOL_NAME}.max-pool-size=${TM_DB_POOL_MAX}" \
    "resources.jdbc-connection-pool.${TM_POOL_NAME}.statement-timeout-in-seconds=${TM_DB_STATEMENT_TIMEOUT}" \
    >/dev/null
echo "    steady ${TM_DB_POOL_STEADY}, max ${TM_DB_POOL_MAX}, statement timeout ${TM_DB_STATEMENT_TIMEOUT}s"

# ---------------------------------------------------------------------------
# Log rotation
# ---------------------------------------------------------------------------
# GlassFish's default keeps three files of 100 MB each, which is 300 MB of disk
# on a machine whose whole database is a fifth of that. It matters more than it
# sounds: the log is on block storage, and a server that fills its disk does not
# fail in a way that names the log.
#
# The attribute names are the new logging framework's, and they are not the ones
# the older documentation uses. GlassFish 7 replaced java.util.logging's file
# handler with org.glassfish.main.jul, so the properties moved from
# `com.sun.enterprise.server.logging.GFFileHandler.*` to
# `org.glassfish.main.jul.handler.GlassFishLogHandler.*`, and the rotation limit
# is now expressed in megabytes rather than bytes. Setting the old name is
# rejected with "Invalid logging attribute name or value", which at least says
# so rather than silently doing nothing.
#
# This is a floor rather than the fix. The reason the log reached 100 MB at all
# is the SQL logging level in persistence.xml, which defaults to quiet as of
# phase 20. This cap is what keeps a future incident from filling the disk
# before anybody notices.
echo
echo "==> Log rotation"
"${ASADMIN}" set-log-attributes \
    "org.glassfish.main.jul.handler.GlassFishLogHandler.rotation.limit.megabytes=${TM_LOG_ROTATION_MB}" >/dev/null
"${ASADMIN}" set-log-attributes \
    "org.glassfish.main.jul.handler.GlassFishLogHandler.rotation.maxArchiveFiles=${TM_LOG_HISTORY_FILES}" >/dev/null
echo "    ${TM_LOG_ROTATION_MB} MB per file, ${TM_LOG_HISTORY_FILES} kept"

# ---------------------------------------------------------------------------
# Say what still needs doing
# ---------------------------------------------------------------------------
echo
if [[ "${JVM_CHANGED}" == "true" ]]; then
    if [[ "${RESTART}" == "true" ]]; then
        echo "==> Restarting the domain to apply the JVM options"
        "${ASADMIN}" stop-domain >/dev/null
        "${ASADMIN}" start-domain >/dev/null
        echo "    Restarted."
    else
        echo "==> JVM options changed; they take effect at the next process start."
        echo "    Restart the domain to apply them:"
        echo "        ./scripts/glassfish-tune.sh --restart"
        echo "    (or scripts/domain.sh stop && scripts/domain.sh start)"
    fi
else
    echo "==> JVM options already matched; nothing needs a restart."
fi
