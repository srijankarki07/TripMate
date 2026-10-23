package com.tripmate.remote;

import java.util.List;

import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.entity.TripRole;

import jakarta.ejb.Remote;

/**
 * Trips and their membership.
 *
 * <p>Membership lives here rather than under the user service because a
 * membership is a property of the trip, and so is the role attached to it.
 *
 * <p>The five CRUD operations cover the {@code trips} table; the member methods
 * cover {@code trip_members}.
 */
@Remote
public interface TripServiceRemote {

    /**
     * Creates a trip and makes the creator its administrator.
     *
     * <p>The trip row and the admin membership are written in one transaction,
     * so a trip can never exist without an administrator. That is the reason
     * this is a single method rather than two calls the caller has to remember
     * to make in the right order.
     *
     * @param trip      the trip to create; {@code tripId} is ignored
     * @param creatorId the user who becomes admin
     * @return the stored trip, with the generated id and the member count of 1
     * @throws IllegalArgumentException if the creator does not exist, or the
     *                                  name is missing
     */
    TripDTO createTrip(TripDTO trip, Long creatorId);

    /** @return the trip, or {@code null} if no such id */
    TripDTO getTrip(Long tripId);

    /**
     * Every trip the user belongs to, newest start date first.
     *
     * <p>Membership is what qualifies a trip, not authorship: a user who was
     * invited sees the trip just as the creator does.
     */
    List<TripDTO> getTripsForUser(Long userId);

    /** @return every trip, for administrative views */
    List<TripDTO> getAllTrips();

    /**
     * Updates the trip's own fields.
     *
     * <p>Membership is not touched; use {@link #addMember} and
     * {@link #removeMember}. Status is updated through here like any other
     * field.
     *
     * @param actedBy who is making the change; must administer the trip
     * @throws ValidationException if the trip does not exist, or the caller does
     *                             not administer it
     */
    TripDTO updateTrip(TripDTO trip, Long actedBy);

    /**
     * Deletes a trip and everything belonging to it.
     *
     * <p>Memberships, itinerary items, expenses with their splits, and
     * settlements all go, because the schema cascades from {@code trips}. The
     * users themselves are untouched.
     *
     * @param actedBy who is deleting it; must administer the trip
     * @throws ValidationException if the trip does not exist, or the caller does
     *                             not administer it
     */
    void deleteTrip(Long tripId, Long actedBy);

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /** @return the members of the trip, ordered by name; empty if none */
    List<TripMemberDTO> getTripMembers(Long tripId);

    /**
     * Adds a user to a trip.
     *
     * <p>Inviting is an administrator action, per the role table in ROADMAP
     * section 4.
     *
     * @param actedBy who is inviting them; must administer the trip
     * @throws ValidationException if the trip or user does not exist, the user
     *                             is already a member, or the caller does not
     *                             administer the trip
     */
    TripMemberDTO addMember(Long tripId, Long userId, TripRole role, Long actedBy);

    /**
     * Removes a user from a trip.
     *
     * <p>Removing is an administrator action, and a trip is not allowed to lose
     * its last administrator: that would leave nobody able to invite, remove or
     * edit, with no way to appoint a replacement through the application.
     *
     * @param actedBy who is removing them; must administer the trip
     * @throws ValidationException if the user is not a member, the caller does
     *                             not administer the trip, or this would leave
     *                             the trip without an administrator
     */
    void removeMember(Long tripId, Long userId, Long actedBy);

    /** @return true if the user belongs to the trip */
    boolean isMember(Long tripId, Long userId);

    /** @return the user's role on the trip, or {@code null} if not a member */
    TripRole getMemberRole(Long tripId, Long userId);

    /**
     * Adds someone to a trip by their exact email address.
     *
     * @throws com.tripmate.exception.ValidationException when the address is
     *         malformed, unknown, or already on the trip
     */
    TripMemberDTO addMemberByEmail(Long tripId, String email, TripRole role, Long actedBy);

    /**
     * Changes what a member is allowed to do.
     *
     * <p>Refuses to demote a trip's last administrator.
     */
    TripMemberDTO updateMemberRole(Long tripId, Long userId, TripRole role, Long actedBy);

    /**
     * Removes the caller from a trip.
     *
     * <p>Refuses when the caller is the trip's last administrator.
     */
    void leaveTrip(Long tripId, Long userId);

    /**
     * Emails every member the trip's closing summary.
     *
     * <p>Administrators only. Returns as soon as the messages are handed to the
     * mail service, which sends them asynchronously.
     *
     * @return how many people it was sent to
     */
    int emailTripSummary(Long tripId, Long actedBy);
}
