package com.tripmate.remote;

import com.tripmate.dto.CalendarSyncResultDTO;
import com.tripmate.dto.GoogleConnectionDTO;

import jakarta.ejb.Remote;

/**
 * A reader's connection to their own Google Calendar.
 *
 * <h2>What this is for, and how it differs from the feed</h2>
 *
 * <p>The feed in {@link CalendarServiceRemote} is a copy of a plan that a
 * calendar application fetches on a schedule of its own. It needs an address
 * pasted into another product, it updates when Google decides to look, and it
 * is one-way by nature.
 *
 * <p>A connection is the reader saying once, here, that TripMate may write into
 * their calendar. There is no address to keep, no application to add, and no
 * waiting: a change made here can be pushed the moment it is made.
 *
 * <p>The credential it leaves behind is by far the most valuable thing this
 * application stores, which shapes everything below: it is the only secret held
 * encrypted, and disconnecting must actually revoke it rather than forgetting it.
 */
@Remote
public interface GoogleCalendarRemote {

    /**
     * Whether this deployment can offer the connection at all.
     *
     * <p>False when no Google client has been configured for it. The page asks
     * so it can explain rather than offer a button that would fail.
     *
     * @return true when the credentials and the encryption key are both present
     */
    boolean isAvailable();

    /**
     * What is known about one reader's connection.
     *
     * @param userId the reader
     * @return their status; never null, and never carrying the token
     * @throws IllegalArgumentException if the user does not exist
     */
    GoogleConnectionDTO status(Long userId);

    /**
     * The address to send a reader to so Google can ask them.
     *
     * <p>Pure: it opens nothing and stores nothing, so the caller can build it,
     * put it in a link, and have the reader's own browser do the asking. The
     * state is the caller's, because keeping it is the caller's job.
     *
     * @param redirectUri where Google should send the reader back, which must
     *                    match a redirect URI registered on the client exactly
     * @param state       an unguessable value the caller will check on return
     * @return an absolute URL to Google's consent screen
     * @throws IllegalArgumentException if this deployment has no client configured
     */
    String authorizeUrl(String redirectUri, String state);

    /**
     * Turns the code Google returned into a stored connection.
     *
     * <p>Exchanges the code for tokens, keeps the refresh token encrypted, and
     * records which account granted it. An existing connection is replaced,
     * which is what reconnecting after a revocation looks like.
     *
     * @param userId      the reader who is connecting
     * @param code        the authorization code from Google's callback
     * @param redirectUri the same value that was sent to {@code authorizeUrl}
     * @throws IllegalArgumentException if the code is refused, the exchange
     *                                  fails, or no key is configured to store
     *                                  the result with
     */
    void connect(Long userId, String code, String redirectUri);

    /**
     * Writes a trip's plan into every connected member's calendar.
     *
     * <p>The whole point of the connection, and the only method here that
     * changes anything at Google. It reconciles rather than appends: an item it
     * has written before is updated in place, and an item that has been removed
     * from the plan takes its event with it. Running it twice with nothing
     * changed writes nothing the second time.
     *
     * <p>Every member who has connected is written to, not only the caller.
     * A trip is shared, and a reader who connected their calendar did so in
     * order to see what the group is planning, not only what they themselves
     * typed.
     *
     * <p>Never throws for a calendar it could not reach. One member having
     * revoked access must not stop the others being updated, and must not fail
     * the save that triggered the sync. What happened is reported instead.
     *
     * @param tripId  the trip to write
     * @param actedBy who is asking; must be a member of the trip
     * @return what was written, per calendar, and what could not be
     * @throws IllegalArgumentException if the caller is not a member of the trip
     */
    CalendarSyncResultDTO syncTrip(Long tripId, Long actedBy);

    /**
     * Writes a trip's plan into every connected member's calendar, in the
     * background.
     *
     * <p>For the sync that follows an itinerary change, where the reader's save
     * has already succeeded and should not wait on Google. {@link #syncTrip} is
     * the blocking form, for the explicit "sync now" where the reader asked for
     * the outcome and expects to wait for it.
     *
     * <p>Returns as soon as the work has been handed off, so it has no result to
     * give. A failure is logged rather than reported, which is deliberate: see
     * {@code MailService.send} for the same reasoning applied to notifications.
     *
     * @param tripId  the trip to write
     * @param actedBy who changed the plan; membership is verified in the
     *                background, and a refusal there is logged, because the
     *                request that caused it has already been answered
     */
    void syncTripLater(Long tripId, Long actedBy);

    /**
     * Forgets a connection, and tells Google to as well.
     *
     * <p>Both halves matter. Deleting the row stops this application using it;
     * revoking it at Google is what makes the credential useless to anyone who
     * already had a copy, and it is the half that a reader asking to disconnect
     * is actually asking for. A revocation that fails does not stop the row
     * being deleted, because leaving the connection in place would be worse.
     *
     * @param userId the reader
     * @throws IllegalArgumentException if the user does not exist
     */
    void disconnect(Long userId);
}
