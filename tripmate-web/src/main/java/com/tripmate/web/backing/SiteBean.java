package com.tripmate.web.backing;

import java.io.Serializable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * The facts about a deployment that the application has to know and cannot
 * discover.
 *
 * <p>Read from system properties, which {@code scripts/glassfish-setup.sh}
 * writes from {@code .env}. A Java EE application has no reliable access to the
 * environment its server was started in, and the container's own configuration
 * is the portable place for a deployment fact; the same artifact then runs in
 * development with the integration checks on and in an assessment with them
 * off, with no code difference.
 *
 * <p>All of them default to the safe answer rather than to the development one:
 * a deployment that has configured nothing gets no link to a page of internal
 * checks, a stack line that says nothing untrue, and no belief in headers that
 * anything could have set.
 */
@Named
@ApplicationScoped
public class SiteBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String SHOW_DIAGNOSTICS = "tripmate.showDiagnostics";
    private static final String FOOTER_STACK = "tripmate.footerStack";
    private static final String TRUST_PROXY = "tripmate.trustProxy";

    /** @return true when this deployment wants the integration checks linked */
    public boolean isShowDiagnostics() {
        return Boolean.parseBoolean(read(SHOW_DIAGNOSTICS, "false"));
    }

    /** @return the stack line under the footer, or an empty string */
    public String getFooterStack() {
        return read(FOOTER_STACK, "");
    }

    /**
     * Whether a proxy in front of this deployment sets the forwarded headers.
     *
     * <p>Off by default. A request carries {@code X-Forwarded-Host} whether or
     * not anything in front of the application put it there, so believing it
     * without being told to would let any caller decide what host the
     * application names when it writes an absolute address of its own. Nothing
     * is exposed by that, since the address is only ever shown back to the
     * caller who asked for it, but it is a lie the application would be
     * repeating, and the whole point of the calendar address is that it is
     * correct.
     *
     * <p>Read on every call rather than cached, so
     * {@code TM_TRUST_PROXY=true ./scripts/glassfish-setup.sh} takes effect
     * without a redeploy.
     *
     * @return true when {@code X-Forwarded-Proto} and {@code X-Forwarded-Host}
     *         may be believed
     */
    public boolean isTrustProxy() {
        return Boolean.parseBoolean(read(TRUST_PROXY, "false"));
    }

    private static String read(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
