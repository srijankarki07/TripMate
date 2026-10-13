package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.remote.HelloServiceRemote;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Phase 0 deployment check: a CDI backing bean that calls the business tier.
 *
 * <p>Every TripMate page follows this shape — an {@code @Named} CDI bean in the
 * web tier delegating to a {@code @Remote} session bean in the business tier,
 * never touching persistence directly (see ROADMAP §12.2).
 */
@Named
@RequestScoped
public class HelloBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Injected by the container from the EJB jar packaged in the same EAR.
     * If this cannot be satisfied GlassFish fails the deployment outright, so a
     * null here means the EAR was assembled wrongly rather than a code error.
     */
    @EJB
    private HelloServiceRemote helloService;

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Re-renders the current view, which re-invokes the EJB with the new name. */
    public String refresh() {
        return null;
    }

    public String getGreeting() {
        if (helloService == null) {
            return "Business tier unavailable — HelloServiceRemote was not injected.";
        }
        return helloService.greet(name);
    }

    public String getRuntimeInfo() {
        if (helloService == null) {
            return "";
        }
        return helloService.describeRuntime();
    }
}
