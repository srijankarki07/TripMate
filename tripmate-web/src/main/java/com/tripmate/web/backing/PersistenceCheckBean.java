package com.tripmate.web.backing;

import java.io.Serializable;
import java.util.List;

import com.tripmate.ejb.PersistenceSmokeTest;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Phase 2 deployment check: runs {@link PersistenceSmokeTest} on demand and
 * renders its report.
 *
 * <p>Temporary scaffolding along with the bean it calls, and removed in Phase 4
 * once the real session beans exist. Request-scoped because the result is
 * wanted for exactly one response — there is no reason to hold a report from a
 * previous run in a session.
 */
@Named
@RequestScoped
public class PersistenceCheckBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private PersistenceSmokeTest smokeTest;

    private List<String> report;
    private String error;

    /**
     * Runs the round-trip. The failure is caught rather than propagated so the
     * page can show what went wrong instead of a Faces error page — the whole
     * point of this check is to make a mapping problem legible.
     */
    public void run() {
        error = null;
        report = null;
        try {
            report = smokeTest.runRoundTrip();
        } catch (RuntimeException ex) {
            error = describe(ex);
        }
    }

    /**
     * Unwraps to the root cause before reporting.
     *
     * <p>The container wraps a persistence failure in an {@code EJBException}
     * whose own message is null, so reporting {@code ex.getMessage()} shows the
     * useless "EJBException: null" and hides the one line that matters — for a
     * bad column mapping, the PSQLException naming the missing column. Walking
     * to the root cause is what makes this page worth having.
     */
    private static String describe(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String root = cause.getClass().getSimpleName()
                + (message == null ? "" : ": " + message);
        return cause == thrown ? root : root + "   (wrapped by " + thrown.getClass().getSimpleName() + ")";
    }

    public List<String> getReport() {
        return report;
    }

    public String getError() {
        return error;
    }

    public boolean isHasRun() {
        return report != null || error != null;
    }

    /** True when the run produced a report and every line passed. */
    public boolean isAllPassed() {
        return report != null && report.stream().noneMatch(line -> line.startsWith("FAIL"));
    }
}
