package com.tripmate.entity;

/**
 * A user's role <em>within one trip</em>.
 *
 * <p>Deliberately not a property of {@link User}. The same person can administer
 * one trip and be an ordinary member of another, which is why this lives on
 * {@link TripMember} rather than on the user record.
 */
public enum TripRole {

    /** May invite and remove members and edit the trip. */
    ADMIN,

    /** May view the trip, add itinerary items and log expenses. */
    MEMBER
}
