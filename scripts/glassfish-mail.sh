#!/usr/bin/env bash
#
# Create the JavaMail resource TripMate sends through:
#
#   mail/TripMate   the session MailService looks up
#
#   ./scripts/glassfish-mail.sh
#
# Idempotent: the resource is replaced only when its settings differ, and the
# password alias is created or updated rather than duplicated.
#
# The resource is created whether or not an SMTP host is configured. That is the
# point: MailService injects the session, and an injection that cannot be
# satisfied fails the deployment rather than the send. So the resource always
# exists, and whether mail actually leaves the server is decided by the
# tripmate.deliver property it carries.
#
# With no TM_MAIL_HOST set, the host is a placeholder and tripmate.deliver is
# false. MailService then renders each message and writes it to the server log.
# A fresh checkout with no credentials deploys, runs and is demonstrable.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

if ! tm_domain_running; then
    echo "==> Starting GlassFish domain"
    "${ASADMIN}" start-domain
fi

# ---------------------------------------------------------------------------
# Is mail configured?
# ---------------------------------------------------------------------------
if [[ -n "${TM_MAIL_HOST}" ]]; then
    DELIVER="true"
    HOST="${TM_MAIL_HOST}"
    echo "==> SMTP host '${TM_MAIL_HOST}:${TM_MAIL_PORT}'"
else
    # Nothing to apply. If a resource already exists, leave it exactly as it is.
    #
    # This branch used to overwrite whatever was configured with a
    # logging-only placeholder, and that was a real fault with a real cost: this
    # script is called by deploy.sh on every deploy, so any deploy run from a
    # shell without the mail variables exported silently turned delivery off.
    # The configuration looked correct in the file, the application looked
    # correct, and the only symptom was mail that never arrived and a line in
    # the server log saying so. It was found by exactly that route.
    #
    # Absence of configuration is not an instruction to unconfigure. A deploy
    # that says nothing about mail must not be able to disable it.
    if "${ASADMIN}" list-mail-resources 2>/dev/null | grep -qix "${TM_MAIL_JNDI}"; then
        echo "==> No TM_MAIL_HOST set, and '${TM_MAIL_JNDI}' already exists"
        echo "==> Leaving it as it is. Export TM_MAIL_HOST to change it, or"
        echo "    delete the resource to fall back to rendering mail into the log."
        exit 0
    fi

    DELIVER="false"
    # A placeholder, because the resource needs a host and an empty one is
    # rejected. Nothing connects to it: tripmate.deliver is false, so MailService
    # never asks the session for a transport. Reached only on a domain that has
    # no mail resource at all, which is a fresh checkout.
    HOST="localhost"
    echo "==> No TM_MAIL_HOST set and no mail resource yet; creating a logging-only one"
fi

# A host and a password are not enough on their own. GlassFish validates the
# server's certificate against the domain's own trust store, which ships with
# two entries and no Google roots, so the STARTTLS handshake fails before
# authentication is attempted and the error names a certificate rather than a
# credential. Done here rather than left to the reader because it is only
# needed when there is a host to connect to.
if [[ "${DELIVER}" == "true" ]]; then
    "${TM_SCRIPT_DIR}/glassfish-trust.sh"
fi

# ---------------------------------------------------------------------------
# The password, as an alias
# ---------------------------------------------------------------------------
# A GlassFish password alias rather than a literal in the resource's property
# list. The alias is stored encrypted in the domain configuration, so the
# password is not readable in domain.xml, and the reference to it,
# ${ALIAS=...}, is resolved by the container when the session is created.
#
# The secret reaches asadmin through a password file on a process-substitution
# descriptor rather than through an argument, so it does not appear in the
# process table where any user on the machine can read it from ps.
ALIAS="tripmateMailPassword"
PASSWORD_REF=""

if [[ -n "${TM_MAIL_PASSWORD}" ]]; then
    # Matched case-insensitively, and that is not a nicety. The server stores
    # alias names folded to lower case, so `list-password-aliases` prints
    # `tripmatemailpassword` for the alias this script calls
    # `tripmateMailPassword`. A case-sensitive comparison therefore never
    # matches, every run takes the "does not exist yet" branch, and the second
    # run dies with "Password alias with the specified name already exists" -
    # which reads like a stale alias rather than like a bug in the check.
    if "${ASADMIN}" list-password-aliases 2>/dev/null | grep -qix "${ALIAS}"; then
        echo "==> Updating password alias '${ALIAS}'"
        "${ASADMIN}" update-password-alias \
            --passwordfile <(printf 'AS_ADMIN_ALIASPASSWORD=%s\n' "${TM_MAIL_PASSWORD}") \
            "${ALIAS}" >/dev/null
    else
        echo "==> Creating password alias '${ALIAS}'"
        "${ASADMIN}" create-password-alias \
            --passwordfile <(printf 'AS_ADMIN_ALIASPASSWORD=%s\n' "${TM_MAIL_PASSWORD}") \
            "${ALIAS}" >/dev/null
    fi
    # The `=` inside the alias reference is escaped, and it has to be. asadmin's
    # property parser rejects a bare `=` anywhere in a value with "Invalid
    # property syntax, "=" in value", and the reference to the alias is written
    # `${ALIAS=name}`, which contains one. Without the escape this script fails
    # at the last step, after it has already created the password alias, so the
    # failure is reported against the property list rather than against the
    # credential that was just stored.
    #
    # Same class of bug as the colon below, and the same fix: the backslash is a
    # parsing artefact of the command line, and the value that ends up in
    # domain.xml is the plain `${ALIAS=name}`, which the container then resolves
    # against the encrypted alias.
    PASSWORD_REF=":mail.smtp.password=\${ALIAS\\=${ALIAS}}"
fi

# ---------------------------------------------------------------------------
# Build the property list
# ---------------------------------------------------------------------------
# Colon-separated key=value pairs, which is the shape asadmin expects.
#
# A colon inside a value has to be escaped with a backslash, because the colon
# is also the separator. The base URL always contains two of them, so without
# this the command fails with "invalid property syntax" naming a fragment of the
# URL rather than the property it came from. The escape is a parsing artefact
# only: the value stored in domain.xml is the plain URL.
BASE_URL_ESCAPED="${TM_APP_BASE_URL//:/\\:}"

# mail.smtp.auth is set only when a user is given, because asking for
# authentication that has no credentials behind it fails at send time with a
# message about the server rather than about the configuration.
PROPS="mail.smtp.port=${TM_MAIL_PORT}"
PROPS="${PROPS}:mail.smtp.starttls.enable=${TM_MAIL_STARTTLS}"
PROPS="${PROPS}:tripmate.deliver=${DELIVER}"
PROPS="${PROPS}:tripmate.baseUrl=${BASE_URL_ESCAPED}"

if [[ -n "${TM_MAIL_USER}" ]]; then
    PROPS="${PROPS}:mail.smtp.auth=true${PASSWORD_REF}"
else
    PROPS="${PROPS}:mail.smtp.auth=false"
fi

# ---------------------------------------------------------------------------
# The resource
# ---------------------------------------------------------------------------
# --fromaddress is what the envelope and the From header carry. --mailuser is
# the account the session authenticates as, which is often not the same address;
# transactional senders routinely authenticate as an API key.
#
# --mailuser is passed even when it is empty, because this server's
# create-mail-resource lists it as required and rejects the command without it.
create_args=(
    --mailhost "${HOST}"
    --mailuser "${TM_MAIL_USER}"
    --fromaddress "${TM_MAIL_FROM}"
    --description "TripMate transactional mail"
    --property "${PROPS}"
)

# Replaced rather than updated when it already exists. asadmin has no supported
# way to change one property of an existing resource, and the alternative is
# deleting and recreating it on every run, which churns the domain config and
# makes the output claim a change that did not happen.
if "${ASADMIN}" list-mail-resources 2>/dev/null | grep -qx "${TM_MAIL_JNDI}"; then
    echo "==> Replacing mail resource '${TM_MAIL_JNDI}'"
    "${ASADMIN}" delete-mail-resource "${TM_MAIL_JNDI}" >/dev/null
fi

echo "==> Creating mail resource '${TM_MAIL_JNDI}'"
"${ASADMIN}" create-mail-resource "${create_args[@]}" "${TM_MAIL_JNDI}" >/dev/null

echo
if [[ "${DELIVER}" == "true" ]]; then
    echo "==> Mail ready: ${TM_MAIL_JNDI} -> ${HOST}:${TM_MAIL_PORT} as ${TM_MAIL_FROM}"
else
    echo "==> Mail ready: ${TM_MAIL_JNDI} (logging only; set TM_MAIL_HOST to send)"
fi
