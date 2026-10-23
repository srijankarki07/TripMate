package com.tripmate.ejb;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.tripmate.calendar.ICalendar;
import com.tripmate.dto.CalendarFeedDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.entity.Trip;
import com.tripmate.entity.TripCalendarFeed;
import com.tripmate.entity.TripStatus;
import com.tripmate.entity.User;
import com.tripmate.exception.ValidationException;
import com.tripmate.remote.CalendarServiceRemote;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.security.TokenHasher;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;

/**
 * Hands a trip's plan to a calendar application, and keeps handing it over.
 *
 * <p>Stateless with container-managed transactions, like every other service
 * here. It reads and renders; it writes nothing but the token that names a feed.
 *
 * <h2>Which trips can leave, and which cannot</h2>
 *
 * <p>{@link #exportItinerary} is offered for a <em>live</em> trip: one being
 * planned or one under way. Both are plans somebody is going to act on, which is
 * the only reason to put one in a calendar at all. A completed trip's itinerary
 * is a record of what happened and a cancelled one's is a plan nobody is
 * following, so neither is offered.
 *
 * <p>This was limited to a trip still being planned at first, on the reasoning
 * that once a trip starts the plan has stopped being a proposal. That was
 * backwards: a trip under way is exactly when somebody needs the day's plan on
 * their phone, and refusing it while allowing next month's was the wrong way
 * round.
 *
 * <p>The feed goes further and serves any status at all. It is not a fresh
 * export but a standing request somebody made on purpose, and a calendar
 * application polling it has no way to tell the reader that the answer has
 * changed to "no". Refusing would leave an error in their calendar and empty the
 * entries they had just filled, which is worse than serving a plan they asked to
 * keep.
 *
 * <h2>What it refuses</h2>
 *
 * <p>As {@link ValidationException}, so the caller gets a sentence and the
 * transaction stays usable: no trip named, no such trip, a caller who is not on
 * it, a trip that is no longer being planned (the download only), and a trip
 * with nothing in its itinerary. The last is refused rather than sent empty,
 * because a calendar file with no events imports as nothing at all and the
 * reader is left thinking the download failed.
 */
@Stateless
public class CalendarService implements CalendarServiceRemote {

    /** The column's width; see the token's own comment for why it is this long. */
    private static final int TOKEN_LENGTH = 64;

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    /**
     * Read for the items to render.
     *
     * <p>Injected rather than queried here, so the itinerary is ordered by the
     * one place that knows how the order is defined, and through the remote
     * interface because that is the only view this bean has of it. There is no
     * cycle: ItineraryService reaches authorisation through the static
     * {@code TripAccess} helper and never through this bean.
     */
    @EJB
    private ItineraryServiceRemote itineraryService;

    // ------------------------------------------------------------------
    // The download
    // ------------------------------------------------------------------

    @Override
    public CalendarFeedDTO exportItinerary(Long tripId, Long actedBy) {
        if (tripId == null) {
            throw new ValidationException("tripId is required");
        }

        // Membership is checked before the trip is read, so that a caller who
        // is not on the trip is told only that, whether or not the trip exists.
        // Reading first and reporting "no such trip" would answer a stranger's
        // question about which trip ids are real, which is the one thing
        // TripAccess.requireMember is written not to do.
        TripAccess.requireMember(em, tripId, actedBy, "export this trip's calendar");

        Trip trip = requireTrip(tripId);
        // Live rather than PLANNING on its own. A trip under way has a plan
        // somebody is following right now, which is the strongest reason there
        // is to have it in a calendar; refusing one while allowing the plan for
        // next month would have been backwards.
        if (!trip.getStatus().isLive()) {
            throw new ValidationException(
                    "only a trip that is still to come or under way can be sent to a calendar");
        }

        List<ItineraryItemDTO> itinerary = itineraryOf(trip);
        if (itinerary.isEmpty()) {
            throw new ValidationException(
                    "there is nothing planned on this trip yet, so there is no calendar to export");
        }
        return feedOf(trip, itinerary);
    }

    // ------------------------------------------------------------------
    // The subscription
    // ------------------------------------------------------------------

    @Override
    public String subscribeToken(Long tripId, Long actedBy) {
        TripAccess.requireMember(em, tripId, actedBy, "subscribe to this trip's calendar");
        TripCalendarFeed feed = findFeed(tripId);
        return feed == null ? null : feed.getToken();
    }

    @Override
    public String subscribe(Long tripId, Long actedBy) {
        // No separate existence check: requireMember finds the trip in order to
        // find the membership, so a caller who gets past this line is on a trip
        // that is there.
        TripAccess.requireMember(em, tripId, actedBy, "subscribe to this trip's calendar");

        TripCalendarFeed existing = findFeed(tripId);
        if (existing != null) {
            return existing.getToken();
        }

        TripCalendarFeed feed = new TripCalendarFeed();
        feed.setTripId(tripId);
        feed.setToken(newToken());
        if (actedBy != null) {
            feed.setCreatedBy(em.find(User.class, actedBy));
        }
        em.persist(feed);
        em.flush();
        return feed.getToken();
    }

    @Override
    public String resetFeed(Long tripId, Long actedBy) {
        TripAccess.requireAdmin(em, tripId, actedBy, "reset this trip's calendar address");

        TripCalendarFeed feed = findFeed(tripId);
        boolean isNew = feed == null;
        if (isNew) {
            // Nothing to take away, so this creates rather than refuses. The
            // caller asked for an address and gets one; refusing would mean the
            // control worked only the second time it was used.
            feed = new TripCalendarFeed();
            feed.setTripId(tripId);
        }
        if (actedBy != null) {
            feed.setCreatedBy(em.find(User.class, actedBy));
        }

        // The unique constraint on the column is what makes this safe: the
        // chance of drawing a token that is already in use is nil, and the
        // database refuses the write rather than the application trusting that.
        feed.setToken(newToken());

        if (isNew) {
            em.persist(feed);
        }
        em.flush();
        return feed.getToken();
    }

    @Override
    public CalendarFeedDTO feedFor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        TripCalendarFeed feed = findFeedByToken(token);
        if (feed == null) {
            return null;
        }
        Trip trip = em.find(Trip.class, feed.getTripId());
        if (trip == null) {
            // The foreign key cascades, so a feed cannot outlive its trip. This
            // is unreachable barring a concurrent delete, and returning null is
            // the same answer as an unknown token, so no caller has to care.
            return null;
        }

        // A trip whose plan has been emptied is served as a calendar with no
        // events rather than refused, and that is deliberately not the same
        // answer the download gives to the same state.
        //
        // A subscription is the source of truth for the calendar it fills, so
        // "there are no events" is a fact it has to be able to state. Saying it
        // empties the reader's copy. Refusing instead, which is what this did at
        // first, answers a poll with an error: the client keeps the copy it
        // already has, the events that were deleted stay in the reader's
        // calendar, and nothing will ever remove them.
        //
        // The download is the other way round, and correctly: a person is
        // watching that one, and a file with no events in it looks like a
        // failure rather than a fact.
        return feedOf(trip, itineraryOf(trip));
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /**
     * Renders one trip's plan.
     *
     * <p>Both callers render the same way, on purpose: the file a reader
     * imports and the file their calendar polls are the same file. Two
     * renderers would be two chances for them to disagree about an event, and
     * the reader would have no way to tell which one their calendar was
     * showing.
     *
     * <p>What differs is what an empty plan means, which is why the check is
     * not in here. A download of nothing is refused, because the reader is
     * watching and a file with no events looks like a failure. A feed of
     * nothing is answered with nothing, because the reader is not watching and
     * an empty calendar file would read to their client as a successful poll
     * saying the plan is empty, which is a worse message than silence.
     */
    private CalendarFeedDTO feedOf(Trip trip, List<ItineraryItemDTO> itinerary) {
        return new CalendarFeedDTO(
                trip.getTripName(),
                ICalendar.render(trip, itinerary, stampOf(trip)));
    }

    private List<ItineraryItemDTO> itineraryOf(Trip trip) {
        return itineraryService.getItineraryForTrip(trip.getTripId());
    }

    /**
     * The time to stamp an item that carries none of its own.
     *
     * <p>Only reachable for a DTO that was built by hand, because the column is
     * {@code NOT NULL} and the entity fills it before every insert. A trip's own
     * creation time is the last moment at which its plan was certainly not
     * older, and unlike the present moment it does not change between two reads
     * of the same unchanged data, which is what the feed's entity tag depends
     * on.
     */
    private static Instant stampOf(Trip trip) {
        LocalDateTime created = trip.getCreatedAt();
        return created == null
                ? Instant.EPOCH
                : created.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Trip requireTrip(Long tripId) {
        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }
        return trip;
    }

    private TripCalendarFeed findFeed(Long tripId) {
        return em.find(TripCalendarFeed.class, tripId);
    }

    private TripCalendarFeed findFeedByToken(String token) {
        try {
            return em.createQuery(
                            "SELECT f FROM TripCalendarFeed f WHERE f.token = :token",
                            TripCalendarFeed.class)
                    .setParameter("token", token)
                    .getSingleResult();
        } catch (NoResultException none) {
            return null;
        }
    }

    /**
     * A fresh token.
     *
     * <p>The same mint the password reset links use, and for the same reason:
     * 256 bits from {@link java.security.SecureRandom}, in URL-safe base64 with no
     * padding, so it survives being pasted into a calendar application and
     * copied out of one by hand.
     *
     * <p>It is stored as it is issued rather than hashed. {@link TripCalendarFeed}
     * carries the argument for that, and it comes down to this: the address has
     * to be shown to its owner again, and the data it guards is in the same
     * database, so a hash would cost the first and buy nothing against the
     * second.
     */
    private static String newToken() {
        String token = TokenHasher.newToken();
        if (token.length() > TOKEN_LENGTH) {
            // Unreachable while TokenHasher mints 32 bytes, and a loud failure
            // rather than a truncated token if that ever changes: half a token
            // is still a token, and one that silently lost its last characters
            // would still work for whoever drew it and be weaker than it says.
            throw new IllegalStateException(
                    "token of " + token.length() + " characters does not fit the column");
        }
        return token;
    }
}
