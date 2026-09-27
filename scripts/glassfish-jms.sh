#!/usr/bin/env bash
#
# Create the Jakarta Messaging resources TripMate needs on GlassFish:
#
#   jms/ExpenseQueueFactory   the connection factory the sender looks up
#   jms/ExpenseQueue          the queue an expense is submitted to
#   jms_ExpenseQueue          its physical destination on the broker
#
#   ./scripts/glassfish-jms.sh
#
# Idempotent: each piece is only created when it is missing.
#
# deploy.sh calls this automatically, because the message-driven bean binds to
# the queue while the application is being deployed. Without the queue the
# deployment fails, and the failure names the missing resource rather than
# anything more helpful.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

if ! tm_domain_running; then
    echo "==> Starting GlassFish domain"
    "${ASADMIN}" start-domain
fi

# ---------------------------------------------------------------------------
# Connection factory
# ---------------------------------------------------------------------------
# A dedicated factory rather than the container's default, so the queue's
# connection settings can be changed without affecting anything else on the
# server.
if "${ASADMIN}" list-jms-resources 2>/dev/null | grep -qx "${TM_JMS_FACTORY}"; then
    echo "==> Connection factory '${TM_JMS_FACTORY}' exists"
else
    echo "==> Creating connection factory '${TM_JMS_FACTORY}'"
    "${ASADMIN}" create-jms-resource \
        --restype jakarta.jms.QueueConnectionFactory \
        --description "TripMate expense queue factory" \
        "${TM_JMS_FACTORY}" >/dev/null
fi

# ---------------------------------------------------------------------------
# Queue resource
# ---------------------------------------------------------------------------
# The Name property is the physical destination on the broker. It is
# deliberately spelled the same way the server spells its own, jms_<name>,
# which is the convention an auto-created destination would follow.
if "${ASADMIN}" list-jms-resources 2>/dev/null | grep -qx "${TM_JMS_QUEUE}"; then
    echo "==> Queue '${TM_JMS_QUEUE}' exists"
else
    echo "==> Creating queue '${TM_JMS_QUEUE}'"
    "${ASADMIN}" create-jms-resource \
        --restype jakarta.jms.Queue \
        --description "TripMate expense submission queue" \
        --property "Name=${TM_JMS_PHYSICAL}" \
        "${TM_JMS_QUEUE}" >/dev/null
fi

# ---------------------------------------------------------------------------
# Physical destination
# ---------------------------------------------------------------------------
# The container can create this on first use, but relying on that means the
# queue only exists once something has already tried to use it. Creating it here
# makes the deployment self-contained and gives the broker log a clear origin.
if "${ASADMIN}" list-jmsdest --desttype queue 2>/dev/null | grep -qx "${TM_JMS_PHYSICAL}"; then
    echo "==> Physical destination '${TM_JMS_PHYSICAL}' exists"
else
    echo "==> Creating physical destination '${TM_JMS_PHYSICAL}'"
    "${ASADMIN}" create-jmsdest --desttype queue "${TM_JMS_PHYSICAL}" >/dev/null
fi

echo
echo "==> Messaging ready: ${TM_JMS_QUEUE} -> ${TM_JMS_PHYSICAL}"
