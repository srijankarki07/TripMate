package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * The address a calendar application subscribes to for one {@link Trip}.
 *
 * <p>Maps to the {@code trip_calendar_feeds} table. At most one row per trip, so
 * the trip's own id is the primary key and there is no separate one.
 *
 * <h2>Why the trip is a column and not an association</h2>
 *
 * <p>Every other mapping in this package points at its parent with a
 * {@code @ManyToOne} and navigates through it. This one holds the id alone,
 * because the row it identifies is the trip's own feed rather than a child of
 * it: there is exactly one, it cannot be moved to another trip, and it is
 * reached by its token or by the trip, never by walking from an object graph.
 * An association here would be a second way to say the same thing, and two
 * ways to say one thing is how they come to disagree.
 *
 * <h2>The token is stored in the clear, deliberately</h2>
 *
 * <p>{@code PasswordReset} and this class both hold a bearer token and store it
 * differently, which is worth the sentence it takes to explain. A reset token
 * is spent the moment it is used and never needs to be shown again, so hashing
 * it costs nothing and protects the table. This one has to be shown to its
 * owner every time they look at it, because people lose the address and the
 * only other way to give it back is to rotate it, which unsubscribes them from
 * their own calendar.
 *
 * <p>What hashing would buy is protection against a leak of this table, and
 * that is worth very little here: the itinerary the token guards is in the same
 * database, in {@code itinerary_items}, two tables away. Anyone who can read
 * this table can read that one. The control that does matter is being able to
 * take the address away again, which {@link com.tripmate.remote.CalendarServiceRemote#resetFeed}
 * does by replacing the token.
 */
@Entity
@Table(name = "trip_calendar_feeds")
public class TripCalendarFeed implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "token", nullable = false, length = 64)
    private String token;

    /**
     * Whoever last issued this address, which is not always whoever first
     * asked for one: replacing it records the administrator who did that.
     *
     * <p>Nullable, and its foreign key is {@code ON DELETE SET NULL}. The feed
     * outlives the account that created it: a subscriber's calendar should not
     * stop updating because the person who set it up closed their account.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TripCalendarFeed feed)) {
            return false;
        }
        return tripId != null && tripId.equals(feed.tripId);
    }

    @Override
    public int hashCode() {
        return TripCalendarFeed.class.hashCode(); // constant; see User.hashCode
    }

    /**
     * Deliberately does not include the token. This string reaches log files,
     * and a bearer credential written into a log is a credential handed to
     * everyone who can read the log.
     */
    @Override
    public String toString() {
        return "TripCalendarFeed{tripId=" + tripId + "}";
    }
}
