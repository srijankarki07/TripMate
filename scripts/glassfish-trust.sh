#!/usr/bin/env bash
#
# Put the Google Trust Services roots into the domain's trust store.
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
# The practical consequence is that SMTP over STARTTLS to Gmail fails with
#
#   PKIX path building failed: unable to find valid certification path
#   to requested target
#
# which reads like a credential problem and is not one. The password can be
# perfect and the send still fails, because the handshake never gets as far as
# authentication. Gmail's certificate chains to GTS Root R1, which is in the
# JDK's store and not in GlassFish's.
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
# Only the Google roots, not the whole JDK store. Syncing all 120-odd entries
# would replace the domain's trust store with a copy that silently goes stale
# the next time the JDK is updated, and it would change what this server trusts
# for every other purpose as a side effect of fixing mail. Adding the three
# roots that mail actually needs keeps the blast radius to the thing that is
# broken.

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

# The three Google Trust Services roots. R1 is the one Gmail currently chains
# to; R3 and R4 are its siblings and are included so that a rotation on
# Google's side does not silently break mail again.
ROOTS=(r1 r3 r4)

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "==> GlassFish trust store: ${TRUSTSTORE}"

for root in "${ROOTS[@]}"; do
    # The JDK's store names each entry for the file it came from in the Debian
    # ca-certificates package. That prefix is the reason a search for "google"
    # finds nothing in there: the aliases are `debian:gts_root_r1.pem`.
    jdk_alias="debian:gts_root_${root}.pem"
    alias="gts_root_${root}"

    if keytool -list -keystore "${TRUSTSTORE}" -storepass "${STOREPASS}" \
            -alias "${alias}" >/dev/null 2>&1; then
        echo "==> ${alias} already trusted"
        continue
    fi

    if ! keytool -exportcert -alias "${jdk_alias}" -keystore "${JDK_STORE}" \
            -storepass changeit -rfc -file "${WORK}/${alias}.pem" >/dev/null 2>&1; then
        echo "error: '${jdk_alias}' is not in ${JDK_STORE}." >&2
        echo "       The JDK's trust store does not have the Google roots." >&2
        exit 1
    fi

    keytool -importcert -noprompt -alias "${alias}" -keystore "${TRUSTSTORE}" \
        -storepass "${STOREPASS}" -file "${WORK}/${alias}.pem" >/dev/null

    echo "==> Trusted ${alias}"
done

echo "==> Trust store ready"
