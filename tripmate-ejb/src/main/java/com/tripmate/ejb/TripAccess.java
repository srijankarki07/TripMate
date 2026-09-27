package com.tripmate.ejb;

import com.tripmate.entity.TripMember;
import com.tripmate.entity.TripRole;
import com.tripmate.exception.ValidationException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

/**
 * The authorization rules of ROADMAP section 8.3.
 *
 * <p>These are the rules that need to know <em>who is asking</em> rather than
 * only what is being asked for, and a stateless session bean cannot work that
 * out by itself. Every operation that depends on them takes the acting user's id
 * as a parameter, which is why several of the service signatures carry an extra
 * argument the ROADMAP's own sketches do not show.
 *
 * <p>Passing the caller explicitly rather than reading it from somewhere
 * implicit is deliberate. A thread-local or a session context would let a caller
 * forget to set it, and the failure mode of a forgotten identity is an
 * authorization check that silently passes. An argument cannot be forgotten: the
 * code does not compile.
 *
 * <p>Both checks throw {@link ValidationException}, so the container does not
 * mark the transaction for rollback. That matters for the message consumer: a
 * rule it can hit has to leave the transaction usable, or the message is
 * redelivered forever. See the Phase 7 notes.
 */
final class TripAccess {

    private TripAccess() {
        // Static helpers; not instantiable.
    }

    /**
     * Requires the user to be a member of the trip.
     *
     * @param action what they were trying to do, used to build the message
     * @throws ValidationException if they are not a member
     */
    static void requireMember(EntityManager em, Long tripId, Long userId, String action) {
        if (userId == null) {
            throw new ValidationException("no user was named, so nobody may " + action);
        }
        if (find(em, tripId, userId) == null) {
            // Deliberately the same message whether the trip is missing or the
            // membership is. Someone who is not on a trip is not entitled to
            // learn whether that trip exists.
            throw new ValidationException(
                    "you are not a member of this trip, so you cannot " + action);
        }
    }

    /**
     * Requires the user to be an administrator of the trip.
     *
     * @throws ValidationException if they are not a member, or are a plain member
     */
    static void requireAdmin(EntityManager em, Long tripId, Long userId, String action) {
        requireMember(em, tripId, userId, action);

        TripMember membership = find(em, tripId, userId);
        if (membership.getRole() != TripRole.ADMIN) {
            throw new ValidationException("only a trip administrator can " + action);
        }
    }

    /** @return the membership, or null if the user is not on the trip */
    static TripMember find(EntityManager em, Long tripId, Long userId) {
        if (tripId == null || userId == null) {
            return null;
        }
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

    /**
     * How many administrators a trip has.
     *
     * <p>Used to stop the last one being removed or demoted, which would leave a
     * trip that nobody can manage: no one able to invite, remove or edit it, and
     * no way to appoint a replacement through the application.
     */
    static long countAdmins(EntityManager em, Long tripId) {
        return em.createQuery(
                        "SELECT COUNT(m) FROM TripMember m "
                                + "WHERE m.trip.tripId = :tripId AND m.role = :role",
                        Long.class)
                .setParameter("tripId", tripId)
                .setParameter("role", TripRole.ADMIN)
                .getSingleResult();
    }
}
