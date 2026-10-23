#!/usr/bin/env bash
#
# Put the roots this application's outbound calls need into the domain's trust
# store. Today that is Google's, for SMTP, and Let's Encrypt's, for the place
# lookup the map uses.
#
#   ./scripts/glassfish-trust.sh
#
# Idempotent: a root that is already trusted is left alone and the command
# reports that rather than failing.
#
# ---------------------------------------------------------------------------
# Why this is needed at all
# ---------------------------------------------------------------------------
# GlassFish does not use the JDK's trust store. Its domain is started with
#
#   -Djavax.net.ssl.trustStore=${com.sun.aas.instanceRoot}/config/cacerts.jks
#
# set as a JVM option, so every TLS connection the server makes is validated
# against that file rather than against $JAVA_HOME/lib/security/cacerts. The
# JDK's store ships with the whole Mozilla root programme; GlassFish's ships
# with two entries, its own self-signed instance certificate and `s1as`.
#
# The practical consequence is that any outbound TLS call fails with
#
#   PKIX path building failed: unable to find valid certification path
#   to requested target
#
# Gmail over STARTTLS was the first to hit it, and it reads like a credential
# problem and is not one: the password can be perfect and the send still fails,
# because the handshake never gets as far as authentication. The place lookup
# the map uses hit the same wall against nominatim.openstreetmap.org, whose
# certificate chains to ISRG Root X1.
#
# One file, two failures, one cause. Anything this application talks to over
# TLS will need its root here, and the answer is always the same: find the root
# in the JDK's store and copy it across.
#
# ---------------------------------------------------------------------------
# Why the certificates are copied out of the JDK's store
# ---------------------------------------------------------------------------
# Rather than downloaded from pki.goog. The JDK's store is already on the
# machine and its roots have already been through the Mozilla review that puts
# them there, so copying from it needs no trust decision of our own and no
# network access at deploy time. Importing a certificate fetched over the
# connection the certificate is meant to secure would be circular anyway.
#
# ---------------------------------------------------------------------------
# Scope
# ---------------------------------------------------------------------------
# A named list, not the whole JDK store. Syncing all 120-odd entries would
# replace the domain's trust store with a copy that silently goes stale the
# next time the JDK is updated, and it would change what this server trusts for
# every other purpose as a side effect of fixing one call. Adding the roots the
# application actually needs keeps the blast radius to the things that are
# broken, and the comment beside each entry says which service it is for, so
# that the next person to add one knows what the list is for.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
tm_require_tools

TRUSTSTORE="${GLASSFISH_HOME}/glassfish/domains/${TM_DOMAIN}/config/cacerts.jks"

if [[ ! -f "${TRUSTSTORE}" ]]; then
    echo "error: no trust store at '${TRUSTSTORE}'." >&2
    echo "       Create the domain first (./scripts/domain.sh)." >&2
    exit 1
fi

# The same well-known password every GlassFish domain ships with. Not a secret:
# the file holds public root certificates and the domain's own self-signed
# certificate, and it is readable only by the account that owns the install.
STOREPASS="changeit"
JDK_STORE="${JAVA_HOME}/lib/security/cacerts"

# Alias in the domain's store, alias in the JDK's store, and what needs it.
#
# The Google roots: R1 is the one Gmail currently chains to; R3 and R4 are its
# siblings, included so that a rotation on Google's side does not silently
# break mail again.
#
# ISRG Root X1 is what Let's Encrypt signs its intermediates with, and it is
# what nominatim.openstreetmap.org chains to. X2 is its sibling, for the same
# reason as R3 and R4.
#
# The JDK aliases carry a `debian:` prefix because that is how the Debian
# ca-certificates package names them, which is also why a search for "google"
# or "letsencrypt" in the JDK's store finds nothing.
# Separated by a pipe, not a colon: the JDK aliases contain colons of their
# own, and splitting on those turns `debian:isrg_root_x1.pem` into two fields.
ROOTS=(
    "gts_root_r1|debian:gts_root_r1.pem|mail"
    "gts_root_r3|debian:gts_root_r3.pem|mail"
    "gts_root_r4|debian:gts_root_r4.pem|mail"
    "isrg_root_x1|debian:isrg_root_x1.pem|the place lookup"
    "isrg_root_x2|debian:isrg_root_x2.pem|the place lookup"
)

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "==> GlassFish trust store: ${TRUSTSTORE}"

for entry in "${ROOTS[@]}"; do
    IFS='|' read -r alias jdk_alias needed_by <<<"${entry}"

    if keytool -list -keystore "${TRUSTSTORE}" -storepass "${STOREPASS}" \
            -alias "${alias}" >/dev/null 2>&1; then
        echo "==> ${alias} already trusted"
        continue
    fi

    if ! keytool -exportcert -alias "${jdk_alias}" -keystore "${JDK_STORE}" \
            -storepass changeit -rfc -file "${WORK}/${alias}.pem" >/dev/null 2>&1; then
        echo "error: '${jdk_alias}' is not in ${JDK_STORE}." >&2
        echo "       ${needed_by} needs it, and the JDK's store does not have it." >&2
        exit 1
    fi

    keytool -importcert -noprompt -alias "${alias}" -keystore "${TRUSTSTORE}" \
        -storepass "${STOREPASS}" -file "${WORK}/${alias}.pem" >/dev/null

    echo "==> Trusted ${alias} (for ${needed_by})"
done

echo "==> Trust store ready"
