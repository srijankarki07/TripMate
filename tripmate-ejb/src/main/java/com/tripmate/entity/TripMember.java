package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Membership of a {@link User} in a {@link Trip}, carrying that user's
 * {@link TripRole} for that trip.
 *
 * <p>Maps to the {@code trip_members} table. This is a join entity with
 * payload — the role and join date belong to the relationship, not to either
 * side of it, which is exactly why the model uses an explicit entity rather
 * than a plain {@code @ManyToMany}.
 */
@Entity
@Table(
        name = "trip_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trip_members",
                columnNames = {"trip_id", "user_id"}))
public class TripMember implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tripMemberSeq")
    @SequenceGenerator(
            name = "tripMemberSeq",
            sequenceName = "trip_members_member_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /**
     * The owning side of the trip relationship. {@code optional = false}
     * because the column is NOT NULL, and it lets the provider skip a join to
     * check existence.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private TripRole role;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @PrePersist
    void prePersist() {
        if (joinedAt == null) {
            joinedAt = LocalDateTime.now();
        }
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public TripRole getRole() {
        return role;
    }

    public void setRole(TripRole role) {
        this.role = role;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    /** Convenience for the "only admins may do this" checks in the EJB tier. */
    public boolean isAdmin() {
        return role == TripRole.ADMIN;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TripMember member)) {
            return false;
        }
        return memberId != null && memberId.equals(member.memberId);
    }

    @Override
    public int hashCode() {
        return TripMember.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "TripMember{memberId=" + memberId + ", role=" + role + "}";
    }
}
