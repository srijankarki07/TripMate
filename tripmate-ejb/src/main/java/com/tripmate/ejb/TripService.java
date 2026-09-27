package com.tripmate.ejb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.entity.Trip;
import com.tripmate.entity.TripMember;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.TripStatus;
import com.tripmate.entity.User;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;

/**
 * Trips and their membership.
 *
 * <p>Stateless with container-managed transactions, so no method starts or
 * commits a transaction of its own. The one place that matters is
 * {@link #createTrip}: the trip and its admin membership are written inside the
 * same transaction, which is what makes a trip without an administrator
 * impossible rather than merely unlikely.
 */
@Stateless
public class TripService implements TripServiceRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    @Override
    public TripDTO createTrip(TripDTO trip, Long creatorId) {
        require(trip, "trip");
        require(creatorId, "creatorId");
        require(trip.getTripName(), "tripName");

        User creator = em.find(User.class, creatorId);
        if (creator == null) {
            throw new IllegalArgumentException("no such user: " + creatorId);
        }

        Trip entity = new Trip();
        entity.setTripName(trip.getTripName().trim());
        entity.setDestination(trip.getDestination());
        entity.setStartDate(trip.getStartDate());
        entity.setEndDate(trip.getEndDate());
        entity.setDescription(trip.getDescription());
        entity.setStatus(trip.getStatus() == null ? TripStatus.PLANNING : trip.getStatus());
        entity.setCreatedBy(creator);

        em.persist(entity);

        // Written in the same transaction as the trip, so the two cannot come
        // apart. Without this the trip would exist with nobody able to manage
        // it.
        TripMember admin = new TripMember();
        admin.setTrip(entity);
        admin.setUser(creator);
        admin.setRole(TripRole.ADMIN);
        em.persist(admin);

        em.flush();
        return TripDTO.from(entity, creator.getFullName(), 1);
    }

    @Override
    public TripDTO getTrip(Long tripId) {
        if (tripId == null) {
            return null;
        }
        Trip trip = em.find(Trip.class, tripId);
        return trip == null ? null : toDto(trip);
    }

    @Override
    public List<TripDTO> getTripsForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // Membership decides, not authorship: an invited user sees the trip
        // exactly as the creator does. createdBy is fetch-joined so resolving
        // the administrator's name costs no extra query per trip.
        List<Trip> trips = em.createQuery(
                        "SELECT DISTINCT t FROM Trip t "
                                + "JOIN FETCH t.createdBy "
                                + "JOIN t.members m "
                                + "WHERE m.user.userId = :userId "
                                + "ORDER BY t.startDate DESC", Trip.class)
                .setParameter("userId", userId)
                .getResultList();
        return toDtos(trips);
    }

    @Override
    public List<TripDTO> getAllTrips() {
        List<Trip> trips = em.createQuery(
                        "SELECT t FROM Trip t LEFT JOIN FETCH t.createdBy "
                                + "ORDER BY t.startDate DESC", Trip.class)
                .getResultList();
        return toDtos(trips);
    }

    @Override
    public TripDTO updateTrip(TripDTO trip) {
        require(trip, "trip");
        require(trip.getTripId(), "tripId");

        Trip entity = em.find(Trip.class, trip.getTripId());
        if (entity == null) {
            throw new IllegalArgumentException("no such trip: " + trip.getTripId());
        }

        if (trip.getTripName() != null) {
            entity.setTripName(trip.getTripName().trim());
        }
        entity.setDestination(trip.getDestination());
        entity.setStartDate(trip.getStartDate());
        entity.setEndDate(trip.getEndDate());
        entity.setDescription(trip.getDescription());
        if (trip.getStatus() != null) {
            entity.setStatus(trip.getStatus());
        }

        em.flush();
        return toDto(entity);
    }

    @Override
    public void deleteTrip(Long tripId) {
        require(tripId, "tripId");

        Trip entity = em.find(Trip.class, tripId);
        if (entity == null) {
            throw new IllegalArgumentException("no such trip: " + tripId);
        }
        // The children are removed by ON DELETE CASCADE in the schema rather
        // than by JPA, because Trip's collections deliberately carry no
        // cascade. Two mechanisms for one job would eventually disagree.
        em.remove(entity);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    @Override
    public List<TripMemberDTO> getTripMembers(Long tripId) {
        if (tripId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT m FROM TripMember m JOIN FETCH m.user "
                                + "WHERE m.trip.tripId = :tripId "
                                + "ORDER BY m.user.fullName", TripMember.class)
                .setParameter("tripId", tripId)
                .getResultList()
                .stream()
                .map(TripMemberDTO::from)
                .toList();
    }

    @Override
    public TripMemberDTO addMember(Long tripId, Long userId, TripRole role) {
        require(tripId, "tripId");
        require(userId, "userId");

        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new IllegalArgumentException("no such trip: " + tripId);
        }
        User user = em.find(User.class, userId);
        if (user == null) {
            throw new IllegalArgumentException("no such user: " + userId);
        }

        // Business rule ROADMAP section 8.3. Checked here as well as by the
        // unique constraint, so the caller gets a sentence rather than a
        // constraint violation. The constraint stays the backstop against two
        // concurrent calls both passing this check.
        if (findMembership(tripId, userId) != null) {
            throw new IllegalArgumentException(
                    user.getFullName() + " is already a member of this trip");
        }

        TripMember member = new TripMember();
        member.setTrip(trip);
        member.setUser(user);
        member.setRole(role == null ? TripRole.MEMBER : role);
        em.persist(member);
        em.flush();

        return TripMemberDTO.from(member);
    }

    @Override
    public void removeMember(Long tripId, Long userId) {
        require(tripId, "tripId");
        require(userId, "userId");

        TripMember member = findMembership(tripId, userId);
        if (member == null) {
            throw new IllegalArgumentException("user " + userId
                    + " is not a member of trip " + tripId);
        }

        // Note for Phase 9: the rule that only an admin may remove a member,
        // and the rule that a trip must keep at least one admin, are not
        // enforced yet. Both need the caller's identity, which this signature
        // does not carry.
        em.remove(member);
        em.flush();
    }

    @Override
    public boolean isMember(Long tripId, Long userId) {
        if (tripId == null || userId == null) {
            return false;
        }
        return findMembership(tripId, userId) != null;
    }

    @Override
    public TripRole getMemberRole(Long tripId, Long userId) {
        TripMember member = tripId == null || userId == null
                ? null
                : findMembership(tripId, userId);
        return member == null ? null : member.getRole();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TripMember findMembership(Long tripId, Long userId) {
        try {
            return em.createQuery(
                            "SELECT m FROM TripMember m "
                                    + "WHERE m.trip.tripId = :tripId AND m.user.userId = :userId",
                            TripMember.class)
                    .setParameter("tripId", tripId)
                    .setParameter("userId", userId)
                    .getSingleResult();
        } catch (NoResultException none) {
            return null;
        }
    }

    /** Single trip: one extra count query is cheaper than a second round trip. */
    private TripDTO toDto(Trip trip) {
        long members = em.createQuery(
                        "SELECT COUNT(m) FROM TripMember m WHERE m.trip.tripId = :id", Long.class)
                .setParameter("id", trip.getTripId())
                .getSingleResult();
        User admin = trip.getCreatedBy();
        return TripDTO.from(trip,
                admin == null ? null : admin.getFullName(),
                (int) members);
    }

    /**
     * Maps a list of trips without the N+1 pattern.
     *
     * <p>Member counts for every trip come back in one grouped query, so
     * listing twenty trips costs two queries rather than twenty-one. The
     * convenience {@code TripDTO.from(trip)} overload would load every member
     * collection just to call {@code size()} on it.
     */
    private List<TripDTO> toDtos(List<Trip> trips) {
        if (trips.isEmpty()) {
            return List.of();
        }

        List<Long> tripIds = trips.stream().map(Trip::getTripId).toList();

        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "SELECT m.trip.tripId, COUNT(m) FROM TripMember m "
                                + "WHERE m.trip.tripId IN :ids GROUP BY m.trip.tripId",
                        Object[].class)
                .setParameter("ids", tripIds)
                .getResultList()) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }

        List<TripDTO> result = new ArrayList<>(trips.size());
        for (Trip trip : trips) {
            User admin = trip.getCreatedBy();
            result.add(TripDTO.from(trip,
                    admin == null ? null : admin.getFullName(),
                    counts.getOrDefault(trip.getTripId(), 0)));
        }
        return result;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
