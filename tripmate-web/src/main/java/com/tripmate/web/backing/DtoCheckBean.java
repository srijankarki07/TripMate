package com.tripmate.web.backing;

import java.io.Serializable;
import java.util.List;

import com.tripmate.ejb.DtoMappingSmokeTest;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Phase 3 deployment check: runs {@link DtoMappingSmokeTest} on demand and
 * renders its report.
 *
 * <p>Temporary scaffolding along with the bean it calls, removed in Phase 4.
 */
@Named
@RequestScoped
public class DtoCheckBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private DtoMappingSmokeTest mappingTest;

    private List<String> report;
    private String error;

    public void run() {
        error = null;
        report = null;
        try {
            report = mappingTest.runMappingChecks();
        } catch (RuntimeException ex) {
            error = describe(ex);
        }
    }

    /**
     * Unwraps to the root cause. The container reports a persistence failure as
     * an {@code EJBException} with a null message, which would hide the one
     * line that identifies the problem.
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

    public boolean isAllPassed() {
        return report != null && report.stream().noneMatch(line -> line.startsWith("FAIL"));
    }
}
