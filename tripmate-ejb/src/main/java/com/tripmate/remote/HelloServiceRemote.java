package com.tripmate.remote;

import jakarta.ejb.Remote;

/**
 * Deployment smoke test for the business tier.
 *
 * <p>Phase 0 uses this bean to prove the whole chain is wired before any domain
 * code exists: the EJB module packages, GlassFish resolves the {@code @Remote}
 * interface, the web tier can reach the bean across the EAR, and a call
 * completes end to end.
 *
 * <p>This is scaffolding, not domain code. Phase 4 replaces it with the five
 * real session beans ({@code UserService}, {@code TripService},
 * {@code ItineraryService}, {@code ExpenseService}, {@code ReportService}) and
 * this interface is deleted.
 */
@Remote
public interface HelloServiceRemote {

    /**
     * @param name the name to greet; blank or {@code null} falls back to a generic greeting
     * @return a greeting produced inside the EJB container
     */
    String greet(String name);

    /**
     * @return a short description of the JVM and container the bean is executing in,
     *         so the deployment can be verified from the browser
     */
    String describeRuntime();
}
