package com.tripmate.ejb;

import jakarta.ejb.Local;

/**
 * The local half of {@link GoogleCalendarService}, used for one thing: letting
 * the bean call its own asynchronous method.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>An EJB cannot invoke its own {@code @Asynchronous} method. The annotation
 * is implemented by an interceptor, and an interceptor only runs when the call
 * arrives through a container-generated proxy. An ordinary
 * {@code this.someAsyncMethod()} goes straight to the method body and runs it
 * on the calling thread, so the work stays synchronous and nothing anywhere
 * reports a problem. The fix is to inject a reference to itself and call
 * through that, which is what {@link GoogleCalendarService} does.
 *
 * <p>That reference has to be typed to a business interface, and the bean's only
 * interface is the remote one. A remote interface cannot carry an
 * {@code @Asynchronous} method: asynchronous invocation is specified for local
 * views only, because a caller that has already received a remote return value
 * has nothing to wait on. Hence a second, local interface for the one method
 * that needs to be asynchronous.
 *
 * <p>It is not part of the application's API and no other bean calls it. The
 * remote interface stays the only way in from the outside, which is the
 * arrangement the rest of the tier uses.
 */
@Local
public interface GoogleCalendarSyncLocal {

    /**
     * Writes a trip's plan into every connected member's calendar, on the
     * container's asynchronous thread, and never throws.
     *
     * @param tripId  the trip to write
     * @param actedBy who changed the plan; membership is checked here, and a
     *                refusal is logged rather than thrown, because the request
     *                that caused this has already been answered
     */
    void syncTripInBackground(Long tripId, Long actedBy);
}
