package com.tripmate.ejb;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tripmate.dto.MapPointDTO;
import com.tripmate.dto.PastTripDTO;
import com.tripmate.entity.PastTrip;
import com.tripmate.entity.Trip;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.User;
import com.tripmate.exception.ValidationException;
import com.tripmate.geo.PlaceLookup;
import com.tripmate.geo.PlaceName;
import com.tripmate.remote.TravelMapRemote;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The data behind the map.
 *
 * <p>Stateless. It reads the reader's trips and their recorded history, and
 * writes two things: where a trip is, and which trips they took before this
 * application existed.
 *
 * <h2>One query per kind of figure, not one per trip</h2>
 *
 * <p>The map shows a count for every trip: items planned, people going, money
 * spent. Asking for those one trip at a time is the classic N+1, and with a
 * reader who has ten trips it is thirty queries to draw one picture. So the
 * trips are read once, and the three sets of figures are read as three grouped
 * queries over the whole set and joined to the trips in memory.
 *
 * <p>{@code IN :ids} with an empty collection is not valid JPQL, so the grouped
 * queries are skipped entirely when the reader has no trips. That is the price
 * of the shape, and it is cheaper than the alternative of a query per trip.
 */
@Stateless
public class TravelMapService implements TravelMapRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Reading the map
    // ------------------------------------------------------------------

    @Override
    public List<MapPointDTO> pointsFor(Long userId) {
        requireUser(userId);

        List<Trip> trips = tripsFor(userId);
        List<Long> tripIds = trips.stream().map(Trip::getTripId).toList();

        // Which of these the reader administers, so the page can offer to place
        // the ones they are allowed to place and say nothing about the rest.
        List<Long> administered = em.createQuery(
                        "SELECT m.trip.tripId FROM TripMember m "
                                + "WHERE m.user.userId = :userId AND m.role = :role",
                        Long.class)
                .setParameter("userId", userId)
                .setParameter("role", TripRole.ADMIN)
                .getResultList();

        Map<Long, Integer> items = tripIds.isEmpty() ? Map.of() : counts(
                "SELECT i.trip.tripId, COUNT(i) FROM ItineraryItem i "
                        + "WHERE i.trip.tripId IN :ids GROUP BY i.trip.tripId",
                tripIds);
        Map<Long, Integer> members = tripIds.isEmpty() ? Map.of() : counts(
                "SELECT m.trip.tripId, COUNT(m) FROM TripMember m "
                        + "WHERE m.trip.tripId IN :ids GROUP BY m.trip.tripId",
                tripIds);
        Map<Long, BigDecimal> spend = tripIds.isEmpty() ? Map.of() : sums(tripIds);

        List<MapPointDTO> points = new ArrayList<>();
        for (Trip trip : trips) {
            MapPointDTO point = new MapPointDTO();
            point.setKind(MapPointDTO.Kind.TRIP);
            point.setId(trip.getTripId());
            point.setName(trip.getTripName());
            point.setPlace(trip.getDestination());
            // The enum's name, not its label, because the page colours by it and
            // the stylesheet's class names are built from these four words.
            point.setStatus(trip.getStatus() == null ? null : trip.getStatus().name());
            point.setStartDate(trip.getStartDate());
            point.setEndDate(trip.getEndDate());
            point.setNote(trip.getDescription());
            point.setItemCount(items.getOrDefault(trip.getTripId(), 0));
            point.setMemberCount(members.getOrDefault(trip.getTripId(), 0));
            point.setTotalSpent(spend.getOrDefault(trip.getTripId(), BigDecimal.ZERO));
            point.setCanPlace(administered.contains(trip.getTripId()));
            point.setLatitude(trip.getLatitude());
            point.setLongitude(trip.getLongitude());
            point.setAreaGeojson(trip.getAreaGeojson());
            points.add(point);
        }

        for (PastTrip past : pastTripEntitiesFor(userId)) {
            MapPointDTO point = new MapPointDTO();
            point.setKind(MapPointDTO.Kind.PAST_TRIP);
            point.setId(past.getPastTripId());
            point.setName(past.getPlaceName());
            point.setPlace(past.getCountry());
            point.setVisitedOn(past.getVisitedOn());
            point.setNote(past.getNote());
            point.setTotalSpent(BigDecimal.ZERO);
            point.setLatitude(past.getLatitude());
            point.setLongitude(past.getLongitude());
            point.setAreaGeojson(past.getAreaGeojson());
            points.add(point);
        }

        return points;
    }

    /** The reader's trips, most recent first, and undated ones last. */
    private List<Trip> tripsFor(Long userId) {
        return em.createQuery(
                        "SELECT m.trip FROM TripMember m "
                                + "WHERE m.user.userId = :userId "
                                + "ORDER BY m.trip.startDate DESC NULLS LAST, m.trip.tripId DESC",
                        Trip.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    private Map<Long, Integer> counts(String jpql, List<Long> ids) {
        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : em.createQuery(jpql, Object[].class)
                .setParameter("ids", ids)
                .getResultList()) {
            result.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return result;
    }

    private Map<Long, BigDecimal> sums(List<Long> ids) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "SELECT e.trip.tripId, SUM(e.amount) FROM Expense e "
                                + "WHERE e.trip.tripId IN :ids GROUP BY e.trip.tripId",
                        Object[].class)
                .setParameter("ids", ids)
                .getResultList()) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Placing a trip
    // ------------------------------------------------------------------

    @Override
    public void placeTrip(Long tripId, Double latitude, Double longitude, Long actedBy) {
        if (tripId == null) {
            throw new ValidationException("tripId is required");
        }
        TripAccess.requireAdmin(em, tripId, actedBy, "put this trip on the map");

        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }

        checkPair(latitude, longitude, false);
        trip.place(latitude, longitude);

        // What the place is, so the map can fill it rather than pin it. Asked
        // here rather than by the caller so that every route to placing a trip
        // gets the same treatment, and best effort: a lookup that fails costs
        // the extent, not the placement.
        if (trip.isPlaced()) {
            trip.setArea(PlaceLookup.describe(latitude, longitude));
        } else {
            trip.setArea(null);
        }
        em.flush();
    }

    // ------------------------------------------------------------------
    // The reader's own history
    // ------------------------------------------------------------------

    @Override
    public PlaceName describe(Double latitude, Double longitude) {
        return PlaceLookup.describe(latitude, longitude);
    }

    @Override
    public List<PastTripDTO> pastTripsFor(Long userId) {
        requireUser(userId);
        return pastTripEntitiesFor(userId).stream().map(PastTripDTO::from).toList();
    }

    private List<PastTrip> pastTripEntitiesFor(Long userId) {
        return em.createQuery(
                        "SELECT p FROM PastTrip p WHERE p.user.userId = :userId "
                                + "ORDER BY p.visitedOn DESC NULLS LAST, p.pastTripId DESC",
                        PastTrip.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public PastTripDTO addPastTrip(PastTripDTO trip, Long userId) {
        requireUser(userId);
        if (trip == null) {
            throw new ValidationException("a place is required");
        }
        if (trip.getPlaceName() == null || trip.getPlaceName().isBlank()) {
            throw new ValidationException("say where you went");
        }

        PastTrip entity = new PastTrip();
        entity.setUser(em.find(User.class, userId));
        entity.setPlaceName(trip.getPlaceName().trim());
        entity.setCountry(blankToNull(trip.getCountry()));
        entity.setVisitedOn(trip.getVisitedOn());
        entity.setNote(blankToNull(trip.getNote()));
        checkPair(trip.getLatitude(), trip.getLongitude(), true);
        entity.place(trip.getLatitude(), trip.getLongitude());
        // Already looked up when the reader picked the spot, and carried here
        // on the DTO. Asking again would be a second call for the same answer.
        entity.setArea(areaOf(trip));

        em.persist(entity);
        em.flush();
        return PastTripDTO.from(entity);
    }

    @Override
    public void removePastTrip(Long pastTripId, Long userId) {
        requireUser(userId);
        if (pastTripId == null) {
            throw new ValidationException("pastTripId is required");
        }

        PastTrip entity = em.find(PastTrip.class, pastTripId);
        if (entity == null) {
            throw new ValidationException("no such place: " + pastTripId);
        }
        // The same sentence whether it is somebody else's or does not exist, for
        // the reason TripAccess.requireMember gives: a reader is not entitled to
        // learn that another reader's record is real.
        if (!entity.getUser().getUserId().equals(userId)) {
            throw new ValidationException("that place is not yours to remove");
        }

        em.remove(entity);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireUser(Long userId) {
        if (userId == null || em.find(User.class, userId) == null) {
            throw new ValidationException("no such user: " + userId);
        }
    }

    /**
     * Checks a pair of coordinates that may be absent, both or neither.
     *
     * <p>Nothing at all means "take it off the map", which for a trip is a thing
     * a reader is allowed to want and for a past trip is the one thing that
     * cannot be missing, since a place with no place on it is not a record of
     * anywhere.
     *
     * <p>Half a pair is always wrong. The schema refuses it too, so catching it
     * here only changes which sentence the reader gets: this one names the
     * problem, and a constraint violation names a table.
     *
     * @param required whether something has to be there
     */
    private static void checkPair(Double latitude, Double longitude, boolean required) {
        boolean hasLatitude = latitude != null;
        boolean hasLongitude = longitude != null;

        if (!hasLatitude && !hasLongitude) {
            if (required) {
                throw new ValidationException("click the map to say where this was");
            }
            return;
        }
        if (hasLatitude != hasLongitude) {
            throw new ValidationException("a place needs both a latitude and a longitude");
        }
        if (latitude < -90 || latitude > 90) {
            throw new ValidationException("a latitude is between -90 and 90");
        }
        if (longitude < -180 || longitude > 180) {
            throw new ValidationException("a longitude is between -180 and 180");
        }
    }

    /** Rebuilds a lookup from a DTO that has already been through one. */
    private static PlaceName areaOf(PastTripDTO trip) {
        if (!trip.isHasArea()) {
            return null;
        }
        PlaceName place = new PlaceName(trip.getPlaceName(), trip.getCountry());
        place.setGeometry(trip.getAreaGeojson());
        return place;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
