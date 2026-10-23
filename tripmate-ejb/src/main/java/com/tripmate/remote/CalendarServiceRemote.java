package com.tripmate.remote;

import com.tripmate.dto.CalendarFeedDTO;

import jakarta.ejb.Remote;

/**
 * A trip's plan as a calendar file, and the address a calendar can subscribe
 * to so that it keeps its copy current.
 *
 * <h2>Two ways out, and why there are two</h2>
 *
 * <p>{@link #exportItinerary} hands back a file. It is the simplest thing that
 * can work: no account with anybody, no address that has to stay reachable, and
 * nothing to keep running afterwards. The reader imports it once.
 *
 * <p>A subscription is what most people actually want. The address is pasted
 * into a calendar application once, and from then on the application fetches it
 * on its own schedule and the plan stays current without anybody doing anything.
 * That is what the other four methods are for, and it costs one thing the
 * download does not: the address has to be reachable from the internet, by a
 * caller with no session and no cookie, which is why it carries a secret token.
 *
 * <p>Separate from {@link ItineraryServiceRemote} rather than more methods on
 * it, because the rules are different in kind. The itinerary service is about
 * editing a plan, which any member may do at any point in a trip's life. This
 * is about taking a copy of it out of the application, and about handing out an
 * address that reads it without asking who is calling.
 */
@Remote
public interface CalendarServiceRemote {

    /**
     * Renders a trip's itinerary as an iCalendar file, for downloading.
     *
     * @param tripId  the trip to export
     * @param actedBy who is asking; must be a member of the trip
     * @return the file, and the trip name to save it under
     * @throws IllegalArgumentException if the trip does not exist, the caller is
     *                                  not a member of it, the trip is neither
     *                                  being planned nor under way, or it has
     *                                  nothing planned yet
     */
    CalendarFeedDTO exportItinerary(Long tripId, Long actedBy);

    /**
     * The token of this trip's existing subscribe address, or null if it has none.
     *
     * <p>Read-only. Looking at a trip must not quietly hand out an address that
     * reads it, so this reports and never creates.
     *
     * @param actedBy who is asking; must be a member of the trip
     * @return the token, or null when the trip has no subscribe address yet
     * @throws IllegalArgumentException if the caller is not a member
     */
    String subscribeToken(Long tripId, Long actedBy);

    /**
     * Gives this trip a subscribe address, creating one if it has none.
     *
     * <p>Any member may do this, because the address grants nothing a member
     * could not already read: it serves the itinerary they can open. Rotating
     * it is an administrator's, because that is the half that takes something
     * away from somebody.
     *
     * <p>Calling it when an address already exists returns that address rather
     * than replacing it, so a member who presses the button twice does not
     * silently unsubscribe everybody who had already added it.
     *
     * @return the token to put in the address
     * @throws IllegalArgumentException if the caller is not a member
     */
    String subscribe(Long tripId, Long actedBy);

    /**
     * Issues a new token for this trip, invalidating the old address.
     *
     * <p>The way to take a subscription away: whoever holds the old address
     * stops being able to read the plan, and everybody who wants to keep it has
     * to add the new one. Administrators only, and the trip's page says plainly
     * that it unsubscribes anyone using the old address.
     *
     * @return the new token
     * @throws IllegalArgumentException if the caller is not an administrator
     */
    String resetFeed(Long tripId, Long actedBy);

    /**
     * Renders the feed an address names.
     *
     * <p>The one method here that does not take a user, because it cannot: the
     * caller is a calendar application's server, fetching on a schedule, with no
     * session and no cookie. The token is the whole of the authorisation, which
     * is why it is 256 bits of random and can be replaced at any time.
     *
     * <p>Unknown tokens return null rather than throwing, so a caller with a
     * stale address is told nothing about whether trips exist.
     *
     * <p>Unlike {@link #exportItinerary}, this does not care what state the trip
     * is in at all. A subscription is a standing request that somebody set up on
     * purpose, and breaking it the moment a trip starts would empty the calendar
     * they had just filled.
     *
     * <p>Nor does it refuse a trip whose plan is empty. It answers with a
     * calendar containing no events, which is how a subscription is told that
     * everything has been deleted; refusing would leave the deleted events in
     * the reader's calendar for good. See {@code CalendarService} for the
     * longer version.
     *
     * @param token the token from the address
     * @return the feed, or null when no trip has that token
     */
    CalendarFeedDTO feedFor(String token);
}
