package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A registered traveller.
 *
 * <p>Maps to the {@code users} table. Note what is <em>not</em> here: no roles,
 * no trip list that is authoritative. Trips are reached through
 * {@link TripMember}, because membership and its role are per-trip.
 */
@Entity
@Table(name = "users")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "userSeq")
    @SequenceGenerator(
            name = "userSeq",
            sequenceName = "users_user_id_seq",
            // MUST be 1. The database sequence increments by 1, and
            // EclipseLink's default allocation size is 50: it would hand out
            // ids 1-50 while the sequence advanced only once, so the second
            // batch would collide with the first. See allocationSize in
            // database/schema.sql's BIGSERIAL columns.
            allocationSize = 1)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "email", nullable = false, length = 150, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * When the address was proved to belong to this person.
     *
     * <p>Null means the account exists but has not been confirmed. Such an
     * account can supply the right password and still not be signed in: see
     * {@code LoginBean}, which sends the holder to the verification page
     * instead of the dashboard.
     *
     * <p>A timestamp rather than a boolean, so the record answers when as well
     * as whether. The cost of the extra column is nothing and a boolean cannot
     * be turned back into a date later.
     */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ------------------------------------------------------------------
    // Inverse side of the relationships. All LAZY: loading a user must not
    // drag in every trip and expense they have ever touched. The owning side
    // of each of these lives on the child entity's foreign key.
    // ------------------------------------------------------------------

    @OneToMany(mappedBy = "createdBy", fetch = FetchType.LAZY)
    private List<Trip> createdTrips = new ArrayList<>();

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<TripMember> memberships = new ArrayList<>();

    @OneToMany(mappedBy = "paidBy", fetch = FetchType.LAZY)
    private List<Expense> paidExpenses = new ArrayList<>();

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<ExpenseSplit> splits = new ArrayList<>();

    /**
     * The database column has {@code DEFAULT NOW()}, but JPA sends an explicit
     * NULL for an unset field, which would override that default. Setting it
     * here keeps the in-memory entity and the stored row in agreement.
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDateTime getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    /** Marks the address confirmed. There is no un-verify: nothing calls that. */
    public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    /** @return whether this account's address has been confirmed */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<Trip> getCreatedTrips() {
        return createdTrips;
    }

    public List<TripMember> getMemberships() {
        return memberships;
    }

    public List<Expense> getPaidExpenses() {
        return paidExpenses;
    }

    public List<ExpenseSplit> getSplits() {
        return splits;
    }

    // ------------------------------------------------------------------
    // Bidirectional helpers
    //
    // With a bidirectional mapping, setting one side and forgetting the other
    // leaves the in-memory graph disagreeing with the database until the next
    // refresh. These keep both sides in step. Use them rather than touching
    // the collections directly.
    // ------------------------------------------------------------------

    public void addCreatedTrip(Trip trip) {
        createdTrips.add(trip);
        trip.setCreatedBy(this);
    }

    public void removeCreatedTrip(Trip trip) {
        createdTrips.remove(trip);
        trip.setCreatedBy(null);
    }

    public void addMembership(TripMember member) {
        memberships.add(member);
        member.setUser(this);
    }

    public void removeMembership(TripMember member) {
        memberships.remove(member);
        member.setUser(null);
    }

    // ------------------------------------------------------------------
    // Identity
    //
    // Entities are compared by primary key. Two unsaved instances are never
    // equal, even if every field matches, because neither has an identity yet.
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return userId != null && userId.equals(user.userId);
    }

    @Override
    public int hashCode() {
        // Deliberately constant per class. An entity's hash code must not
        // change when the database assigns its id, or an instance already held
        // in a HashSet would become unreachable.
        return User.class.hashCode();
    }

    @Override
    public String toString() {
        return "User{userId=" + userId + ", email='" + email + "'}";
    }
}
