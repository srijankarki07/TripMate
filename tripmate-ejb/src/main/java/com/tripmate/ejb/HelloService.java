package com.tripmate.ejb;

import com.tripmate.remote.HelloServiceRemote;
import jakarta.ejb.Stateless;

/**
 * Phase 0 deployment smoke test — see {@link HelloServiceRemote}.
 *
 * <p>Stateless because it holds no conversational state, which is also the
 * rationale for every session bean in TripMate (see ROADMAP §18.1).
 */
@Stateless
public class HelloService implements HelloServiceRemote {

    private static final String DEFAULT_NAME = "traveller";

    @Override
    public String greet(String name) {
        String who = (name == null || name.isBlank()) ? DEFAULT_NAME : name.trim();
        return "Hello, " + who + " — the TripMate business tier is live.";
    }

    @Override
    public String describeRuntime() {
        return "Stateless session bean reachable | Java "
                + System.getProperty("java.version")
                + " | " + System.getProperty("java.vendor");
    }
}
