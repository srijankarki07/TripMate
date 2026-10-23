package com.tripmate.web.backing;

import java.io.Serializable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * The two facts about a deployment that the footer shows.
 *
 * <p>Read from system properties, which {@code scripts/glassfish-setup.sh}
 * writes from {@code .env}. A Java EE application has no reliable access to the
 * environment its server was started in, and the container's own configuration
 * is the portable place for a deployment fact; the same artifact then runs in
 * development with the integration checks on and in an assessment with them
 * off, with no code difference.
 *
 * <p>Both default to the safe answer rather than to the development one: a
 * deployment that has configured nothing gets no link to a page of internal
 * checks, and a stack line that says nothing untrue.
 */
@Named
@ApplicationScoped
public class SiteBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String SHOW_DIAGNOSTICS = "tripmate.showDiagnostics";
    private static final String FOOTER_STACK = "tripmate.footerStack";

    /** @return true when this deployment wants the integration checks linked */
    public boolean isShowDiagnostics() {
        return Boolean.parseBoolean(read(SHOW_DIAGNOSTICS, "false"));
    }

    /** @return the stack line under the footer, or an empty string */
    public String getFooterStack() {
        return read(FOOTER_STACK, "");
    }

    private static String read(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
