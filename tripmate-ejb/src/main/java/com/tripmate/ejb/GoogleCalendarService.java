package com.tripmate.ejb;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tripmate.calendar.GoogleEvents;
import com.tripmate.dto.CalendarSyncResultDTO;
import com.tripmate.dto.GoogleConnectionDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.entity.GoogleCalendarEvent;
import com.tripmate.entity.GoogleCalendarLink;
import com.tripmate.entity.Trip;
import com.tripmate.entity.User;
import com.tripmate.exception.ValidationException;
import com.tripmate.remote.GoogleCalendarRemote;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.security.TokenCipher;

import jakarta.ejb.Asynchronous;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Connects a reader's Google Calendar, and can take the connection away again.
 *
 * <p>Stateless. The connection itself is a row; this class only does the three
 * things that need a network: asking Google for consent, exchanging the code it
 * returns, and revoking what was granted.
 *
 * <h2>What is configured, and where</h2>
 *
 * <p>A Google client id and secret, and a key for encrypting what comes back.
 * All three are deployment facts read from system properties, the same way the
 * mail settings and the proxy setting are, because a Java EE application has no
 * portable access to the environment its server was started in and none of the
 * three belongs in the repository.
 *
 * <ul>
 * <li>{@code tripmate.google.clientId}</li>
 * <li>{@code tripmate.google.clientSecret}</li>
 * <li>{@code tripmate.credentialsKey}, shared with anything else that stores a
 * secret recoverably</li>
 * </ul>
 *
 * <p>With any of them missing the feature reports itself unavailable rather than
 * failing halfway through: {@link #isAvailable()} is asked before a reader is
 * offered anything, and {@link #connect} checks again before it calls Google, so
 * a grant is never obtained and then discovered to be unstorable.
 *
 * <h2>The token is the whole point of this class</h2>
 *
 * <p>A refresh token grants access to the reader's entire calendar. It is
 * encrypted before it is written and decrypted only to be presented to Google.
 * It is not logged, not put on a DTO, and not included in any exception message,
 * because all three are ways it would leave the building.
 */
@Stateless
public class GoogleCalendarService implements GoogleCalendarRemote, GoogleCalendarSyncLocal {

    private static final String CLIENT_ID = "tripmate.google.clientId";
    private static final String CLIENT_SECRET = "tripmate.google.clientSecret";
    private static final String CREDENTIALS_KEY = "tripmate.credentialsKey";

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo";

    /**
     * The least that will do the job.
     *
     * <p>{@code calendar.events} reaches only events in calendars the reader
     * already has, which is all this needs: it never creates a calendar, never
     * reads one it was not told to write to, and never touches settings.
     * {@code openid} and {@code email} are there so the page can say which
     * account is connected, which is the difference between a reader being able
     * to check and having to remember.
     */
    private static final String CALENDAR_EVENTS =
            "https://www.googleapis.com/auth/calendar.events";

    private static final String SCOPE = "openid email " + CALENDAR_EVENTS;

    /** Long enough for a slow network, short enough not to hold a request thread all day. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Shared and reused, because building one per call would repeat a TLS
     * handshake each time. Thread-safe by contract, and immutable here.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    /**
     * Read for the items to write, and for the order they are in.
     *
     * <p>The same injection {@code TripService} and {@code CalendarService} use,
     * for the same reasons: one place decides how a plan is ordered, and the
     * remote interface is the only view of that bean there is.
     */
    @EJB
    private ItineraryServiceRemote itineraryService;

    /**
     * This bean's own reference, used only to call itself asynchronously.
     *
     * <p>{@link #syncTripLater} has to reach {@link #syncTripInBackground}
     * through the container, because {@code @Asynchronous} is implemented by an
     * interceptor that only runs when the call arrives on a proxy. An ordinary
     * {@code this.syncTripInBackground(...)} would bypass it entirely and run
     * the whole sync on the caller's thread, which is the one thing this is
     * meant to prevent. Nothing about the call looks wrong if that happens; it
     * just quietly stays synchronous.
     *
     * <p>Typed to the local interface rather than to this class, because a bean
     * that declares a business interface has no no-interface view to inject, and
     * an {@code @Asynchronous} method cannot live on the remote one. See
     * {@link GoogleCalendarSyncLocal}.
     */
    @EJB
    private GoogleCalendarSyncLocal self;

    // ------------------------------------------------------------------
    // Writing a trip into a calendar, without making the reader wait
    // ------------------------------------------------------------------

    /**
     * Starts a sync and returns without waiting for it.
     *
     * <p>For the automatic sync that follows an itinerary change. Nothing about
     * the reader's save depends on the calendars being written, and the sync is
     * the slowest thing the application does: one token exchange plus one HTTP
     * call per itinerary item, per connected member, each of which may take
     * twenty seconds before it gives up. Waiting for that is what made adding a
     * stop to a plan feel like the application had hung.
     *
     * <p>This is the same trade {@code MailService.send} makes, for the same
     * reason: the action that caused it has already succeeded and is not
     * reported as having failed because a third party was slow. What the
     * reader loses is the running commentary; {@link #syncTrip} is still there,
     * unchanged, for the explicit "sync now", where the wait is expected and the
     * outcome is what was asked for.
     *
     * <p>Deliberately returns nothing. There is no result yet to return, and
     * inventing an empty one would invite a caller to report it.
     *
     * @param tripId  the trip to write
     * @param actedBy who changed the plan; their membership is checked in the
     *                background, where a refusal is logged rather than thrown
     *                at a request that is already over
     */
    @Override
    public void syncTripLater(Long tripId, Long actedBy) {
        // Through the container, so the interceptor sees it. See the self field.
        self.syncTripInBackground(tripId, actedBy);
    }

    /**
     * The asynchronous half of {@link #syncTripLater}.
     *
     * <p>Runs on the container's asynchronous thread, in its own transaction,
     * and never throws: an exception out of here would have nowhere to go, and
     * failing the reader's request for it is exactly what this exists to stop.
     * A failure is logged with the trip, so it is still discoverable.
     */
    @Asynchronous
    @Override
    public void syncTripInBackground(Long tripId, Long actedBy) {
        try {
            CalendarSyncResultDTO result = syncTrip(tripId, actedBy);
            if (result.getFailed() > 0) {
                java.util.logging.Logger.getLogger(GoogleCalendarService.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "Background calendar sync for trip " + tripId
                                        + " could not write every calendar: "
                                        + result.describe());
            }
        } catch (RuntimeException failed) {
            java.util.logging.Logger.getLogger(GoogleCalendarService.class.getName())
                    .log(java.util.logging.Level.WARNING,
                            "Background calendar sync for trip " + tripId + " failed",
                            failed);
        }
    }

    // ------------------------------------------------------------------
    // Writing a trip into a calendar, on the caller's thread
    // ------------------------------------------------------------------

    @Override
    public CalendarSyncResultDTO syncTrip(Long tripId, Long actedBy) {
        TripAccess.requireMember(em, tripId, actedBy, "write this trip into a calendar");

        CalendarSyncResultDTO result = new CalendarSyncResultDTO();
        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }

        List<ItineraryItemDTO> itinerary = itineraryService.getItineraryForTrip(tripId);
        ZoneId zone = trip.effectiveZone();

        // Every member who has connected, not only the caller. A trip is shared,
        // and somebody who connected their calendar did it to see the group's
        // plan rather than only their own typing.
        List<Long> connected = connectedMembers(tripId);
        result.setCalendars(connected.size());

        String key = read(CREDENTIALS_KEY);
        for (Long userId : connected) {
            GoogleCalendarLink link = em.find(GoogleCalendarLink.class, userId);
            if (link == null) {
                // Disconnected between the query and here. Not a failure: the
                // reader asked for it.
                result.setCalendars(result.getCalendars() - 1);
                continue;
            }
            try {
                syncOne(userId, link, key, itinerary, zone, result);
            } catch (RuntimeException failed) {
                // One calendar that cannot be written must not stop the others,
                // and must not fail the save that asked for this. Whoever it was
                // is counted, with the reason, and the detail is logged.
                result.addFailure(failed.getMessage());
                java.util.logging.Logger.getLogger(GoogleCalendarService.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "Could not sync trip " + tripId + " to a connected calendar",
                                failed);
            }
        }
        return result;
    }

    /**
     * Brings one reader's calendar into line with the plan.
     *
     * <p>A reconciliation rather than a rewrite. Each item is matched against
     * the row recording the event it became, and the three cases are handled as
     * what they are: an item with no row is created, an item with a row is
     * updated in place, and a row whose item has gone is an event to remove.
     *
     * <p>Nothing is deleted and re-created to make the code simpler. That would
     * work, and every sync would send the reader a fresh set of events with new
     * identities, losing anything they had attached to the old ones and
     * re-notifying anybody who had been invited.
     */
    private void syncOne(Long userId, GoogleCalendarLink link, String key,
            List<ItineraryItemDTO> itinerary, ZoneId zone, CalendarSyncResultDTO result) {

        String accessToken = accessToken(TokenCipher.decrypt(link.getRefreshToken(), key));
        String calendarId = link.getCalendarId() == null ? "primary" : link.getCalendarId();

        Map<Long, GoogleCalendarEvent> known = new HashMap<>();
        for (GoogleCalendarEvent row : eventsFor(userId)) {
            if (row.getItemId() == null) {
                // The item is gone, so the event should be too. This is the one
                // moment the row is good for, and it is deleted once it has been
                // used.
                GoogleEvents.delete(accessToken, row.getCalendarId(), row.getGoogleEventId());
                em.remove(row);
                result.setRemoved(result.getRemoved() + 1);
            } else {
                known.put(row.getItemId(), row);
            }
        }

        for (ItineraryItemDTO item : itinerary) {
            GoogleCalendarEvent row = known.remove(item.getItemId());
            if (row == null) {
                String eventId = GoogleEvents.create(accessToken, calendarId, item, zone);
                GoogleCalendarEvent created = new GoogleCalendarEvent();
                created.setUserId(userId);
                created.setItemId(item.getItemId());
                created.setCalendarId(calendarId);
                created.setGoogleEventId(eventId);
                em.persist(created);
                result.setWritten(result.getWritten() + 1);
                continue;
            }
            try {
                GoogleEvents.update(accessToken, row.getCalendarId(), row.getGoogleEventId(),
                        item, zone);
                row.setUpdatedAt(LocalDateTime.now());
                result.setWritten(result.getWritten() + 1);
            } catch (GoogleEvents.Refused gone) {
                if (!gone.isGone()) {
                    throw gone;
                }
                // The row points at an event that is no longer there, which
                // means somebody deleted it in Google. The plan still has the
                // item, so it is written again rather than forgotten.
                row.setGoogleEventId(GoogleEvents.create(accessToken, calendarId, item, zone));
                row.setCalendarId(calendarId);
                row.setUpdatedAt(LocalDateTime.now());
                result.setWritten(result.getWritten() + 1);
            }
        }

        em.flush();
    }

    /** @return the trip's members who have connected a calendar, oldest connection first */
    private List<Long> connectedMembers(Long tripId) {
        return em.createQuery(
                        "SELECT l.userId FROM GoogleCalendarLink l, TripMember m "
                                + "WHERE m.trip.tripId = :tripId AND m.user.userId = l.userId "
                                + "ORDER BY l.connectedAt ASC",
                        Long.class)
                .setParameter("tripId", tripId)
                .getResultList();
    }

    private List<GoogleCalendarEvent> eventsFor(Long userId) {
        return em.createQuery(
                        "SELECT e FROM GoogleCalendarEvent e WHERE e.userId = :userId",
                        GoogleCalendarEvent.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    /**
     * A fresh access token for a stored refresh token.
     *
     * <p>Every sync asks for its own rather than keeping one. An access token
     * lasts an hour and this application is not running a background job, so a
     * cached one would be wrong more often than it was right, and holding a live
     * credential in memory between requests to save one HTTP call is a poor
     * trade.
     *
     * @throws ValidationException when Google will not issue one, which for
     *                             {@code invalid_grant} means the reader has
     *                             revoked access or the Testing-mode expiry has
     *                             passed, and the only fix is reconnecting
     */
    private String accessToken(String refreshToken) {
        List<String> form = new ArrayList<>();
        form.add("client_id=" + encode(read(CLIENT_ID)));
        form.add("client_secret=" + encode(read(CLIENT_SECRET)));
        form.add("refresh_token=" + encode(refreshToken));
        form.add("grant_type=refresh_token");

        JsonObject tokens = post(TOKEN_ENDPOINT, String.join("&", form), null,
                "refresh its access");
        String accessToken = string(tokens, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new ValidationException(
                    "Google did not return an access token. The calendar connection may have "
                            + "been revoked; disconnecting and connecting again will fix it.");
        }
        return accessToken;
    }

    // ------------------------------------------------------------------
    // What the page asks
    // ------------------------------------------------------------------

    @Override
    public boolean isAvailable() {
        return hasClient() && TokenCipher.isUsable(read(CREDENTIALS_KEY));
    }

    @Override
    public GoogleConnectionDTO status(Long userId) {
        requireUser(userId);
        GoogleCalendarLink link = em.find(GoogleCalendarLink.class, userId);

        return new GoogleConnectionDTO(
                isAvailable(),
                link != null,
                link == null ? null : link.getGoogleEmail(),
                link == null ? null : link.getConnectedAt());
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    @Override
    public String authorizeUrl(String redirectUri, String state) {
        if (!hasClient()) {
            throw new ValidationException(
                    "no Google client is configured for this deployment");
        }
        if (redirectUri == null || redirectUri.isBlank() || state == null || state.isBlank()) {
            throw new ValidationException("a redirect address and a state are both required");
        }

        List<String> parameters = new ArrayList<>();
        parameters.add("client_id=" + encode(read(CLIENT_ID)));
        parameters.add("redirect_uri=" + encode(redirectUri));
        parameters.add("response_type=code");
        parameters.add("scope=" + encode(SCOPE));
        // Without offline there is no refresh token, only an hour of access, and
        // without consent Google may answer a second connection with a token it
        // has already issued and the application cannot use.
        parameters.add("access_type=offline");
        parameters.add("prompt=consent");
        parameters.add("include_granted_scopes=true");
        parameters.add("state=" + encode(state));

        return AUTH_ENDPOINT + "?" + String.join("&", parameters);
    }

    @Override
    public void connect(Long userId, String code, String redirectUri) {
        requireUser(userId);
        if (!isAvailable()) {
            throw new ValidationException(
                    "this deployment has no Google client configured, or no key to store the "
                            + "connection with, so a connection cannot be made");
        }
        if (code == null || code.isBlank()) {
            throw new ValidationException("no authorization code was returned");
        }

        // Checked before the exchange rather than after: a grant obtained and
        // then unstorable would leave the reader's account connected to
        // something that cannot use it and will not say so.
        String key = read(CREDENTIALS_KEY);
        if (!TokenCipher.isUsable(key)) {
            throw new ValidationException(
                    "no credential key is configured, so a connection cannot be stored");
        }

        JsonObject tokens = exchangeCode(code, redirectUri);

        // Whether the reader actually granted the calendar, asked here rather
        // than found out later.
        //
        // Google's consent screen lists the permissions with a checkbox each,
        // and they arrive unticked: there is no parameter that preselects them,
        // which Google removed deliberately so that an application cannot help
        // itself to access. A reader who ticks the profile permissions and not
        // the calendar one gets a token that works and cannot write anything,
        // and the failure surfaces much later as an unexplained refusal from
        // the Calendar API. Catching it here turns that into one sentence
        // naming the box to tick.
        //
        // A scope that is absent means the granted set is identical to the
        // requested set (RFC 6749 section 5.1), so it is the good case rather
        // than an unknown one.
        String granted = string(tokens, "scope");
        if (granted != null && !granted.contains(CALENDAR_EVENTS)) {
            throw new ValidationException(
                    "Google did not grant access to your calendar, so nothing was connected. "
                            + "When Google shows the list of permissions, tick the box beside "
                            + "Calendar as well as the others, then connect again.");
        }

        String refreshToken = string(tokens, "refresh_token");
        if (refreshToken == null || refreshToken.isBlank()) {
            // Google withholds a refresh token when it believes one has already
            // been issued for this client and account. Saying so is the only way
            // the reader can act, since the fix is at their end.
            throw new ValidationException(
                    "Google did not return a lasting token. Remove TripMate's access at "
                            + "myaccount.google.com/permissions and connect again.");
        }

        String email = lookupEmail(string(tokens, "access_token"));

        GoogleCalendarLink link = em.find(GoogleCalendarLink.class, userId);
        if (link == null) {
            link = new GoogleCalendarLink();
            link.setUserId(userId);
        }
        link.setRefreshToken(TokenCipher.encrypt(refreshToken, key));
        link.setGoogleEmail(email);
        if (link.getCalendarId() == null || link.getCalendarId().isBlank()) {
            link.setCalendarId("primary");
        }

        if (em.contains(link)) {
            em.merge(link);
        } else {
            em.persist(link);
        }
        em.flush();
    }

    @Override
    public void disconnect(Long userId) {
        requireUser(userId);
        GoogleCalendarLink link = em.find(GoogleCalendarLink.class, userId);
        if (link == null) {
            return;
        }

        // Revoked first, and the row removed whatever that did. A revocation
        // that fails leaves the reader connected at Google, which is a smaller
        // problem than a row this application keeps using after being asked to
        // stop.
        String key = read(CREDENTIALS_KEY);
        if (TokenCipher.isUsable(key)) {
            try {
                revoke(TokenCipher.decrypt(link.getRefreshToken(), key));
            } catch (RuntimeException couldNotRevoke) {
                // Logged and stepped over. See above for why this is not fatal.
                java.util.logging.Logger.getLogger(GoogleCalendarService.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "Could not revoke a Google grant; the local connection is "
                                        + "being removed anyway", couldNotRevoke);
            }
        }

        em.remove(link);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Talking to Google
    // ------------------------------------------------------------------

    /** Exchanges an authorization code for tokens. */
    private JsonObject exchangeCode(String code, String redirectUri) {
        List<String> form = new ArrayList<>();
        form.add("code=" + encode(code));
        form.add("client_id=" + encode(read(CLIENT_ID)));
        form.add("client_secret=" + encode(read(CLIENT_SECRET)));
        form.add("redirect_uri=" + encode(redirectUri == null ? "" : redirectUri));
        form.add("grant_type=authorization_code");

        return post(TOKEN_ENDPOINT, String.join("&", form), null, "connect to Google Calendar");
    }

    /** Tells Google to forget a grant. */
    private void revoke(String token) {
        post(REVOKE_ENDPOINT, "token=" + encode(token), null, "disconnect from Google Calendar");
    }

    /**
     * Asks Google which account granted the connection.
     *
     * <p>Best effort. Not knowing the address is a cosmetic loss, and refusing
     * the whole connection over it would be a poor trade for a reader who has
     * just given permission.
     *
     * @return the email, or null
     */
    private String lookupEmail(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(USERINFO_ENDPOINT))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return null;
            }
            return string(parse(response.body()), "email");
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Posts a form to Google and returns the parsed answer.
     *
     * <p>One place for the four things every call here needs: the form content
     * type, a timeout, turning a failure into a sentence a person can read, and
     * never putting the response body somewhere it could be mistaken for
     * something safe to show. Google's error bodies describe the request, and
     * the request contains the client secret, so the body is logged and the
     * reader is given the status.
     */
    private JsonObject post(String endpoint, String form, String bearer, String what) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            throw new ValidationException("could not reach Google to " + what, failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ValidationException("the request to " + what + " was interrupted",
                    interrupted);
        }

        if (response.statusCode() / 100 != 2) {
            java.util.logging.Logger.getLogger(GoogleCalendarService.class.getName())
                    .warning("Google refused to " + what + ": HTTP " + response.statusCode()
                            + " " + describeError(response.body()));
            throw new ValidationException(
                    "Google refused the request to " + what + " (HTTP " + response.statusCode()
                            + "). Check the client id, the secret and the redirect address.");
        }
        return parse(response.body());
    }

    private static String describeError(String body) {
        try {
            JsonObject json = parse(body);
            String error = string(json, "error");
            String description = string(json, "error_description");
            return (error == null ? "" : error) + (description == null ? "" : ": " + description);
        } catch (RuntimeException notJson) {
            return "(response was not JSON)";
        }
    }

    private static JsonObject parse(String body) {
        try (JsonReader reader = Json.createReader(new java.io.StringReader(body))) {
            return reader.readObject();
        } catch (RuntimeException notJson) {
            throw new ValidationException("Google's answer could not be read");
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void requireUser(Long userId) {
        if (userId == null || em.find(User.class, userId) == null) {
            throw new ValidationException("no such user: " + userId);
        }
    }

    private static boolean hasClient() {
        return isPresent(read(CLIENT_ID)) && isPresent(read(CLIENT_SECRET));
    }

    private static String read(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String string(JsonObject json, String name) {
        if (json == null) {
            return null;
        }
        jakarta.json.JsonValue value = json.get(name);
        return value instanceof JsonString text ? text.getString() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
