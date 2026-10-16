package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A planned or completed group trip, and the root of the domain: itinerary
 * items, expenses and settlements all hang off it.
 *
 * <p>Maps to the {@code trips} table.
 *
 * <p>The child collections are LAZY and carry <em>no</em> cascade, which is a
 * deliberate choice. Deleting a trip already removes its children through
 * {@code ON DELETE CASCADE} in the schema, so JPA-level cascade would only add
 * a second, competing mechanism for the same job. The one exception is
 * {@link Expense#getSplits()}, where cascade is genuinely needed because a
 * split has no independent existence.
 */
@Entity
@Table(name = "trips")
public class Trip implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tripSeq")
    @SequenceGenerator(
            name = "tripSeq",
            sequenceName = "trips_trip_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "trip_name", nullable = false, length = 150)
    private String tripName;

    @Column(name = "destination", length = 150)
    private String destination;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TripStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ------------------------------------------------------------------
    // Children
    // ------------------------------------------------------------------

    @OneToMany(mappedBy = "trip", fetch = FetchType.LAZY)
    private List<TripMember> members = new ArrayList<>();

    @OneToMany(mappedBy = "trip", fetch = FetchType.LAZY)
    private List<ItineraryItem> itineraryItems = new ArrayList<>();

    @OneToMany(mappedBy = "trip", fetch = FetchType.LAZY)
    private List<Expense> expenses = new ArrayList<>();

    @OneToMany(mappedBy = "trip", fetch = FetchType.LAZY)
    private List<Settlement> settlements = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = TripStatus.PLANNING;
        }
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getTripName() {
        return tripName;
    }

    public void setTripName(String tripName) {
        this.tripName = tripName;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
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

    public List<TripMember> getMembers() {
        return members;
    }

    public List<ItineraryItem> getItineraryItems() {
        return itineraryItems;
    }

    public List<Expense> getExpenses() {
        return expenses;
    }

    public List<Settlement> getSettlements() {
        return settlements;
    }

    // ------------------------------------------------------------------
    // Bidirectional helpers
    // ------------------------------------------------------------------

    public void addMember(TripMember member) {
        members.add(member);
        member.setTrip(this);
    }

    public void removeMember(TripMember member) {
        members.remove(member);
        member.setTrip(null);
    }

    public void addItineraryItem(ItineraryItem item) {
        itineraryItems.add(item);
        item.setTrip(this);
    }

    public void removeItineraryItem(ItineraryItem item) {
        itineraryItems.remove(item);
        item.setTrip(null);
    }

    public void addExpense(Expense expense) {
        expenses.add(expense);
        expense.setTrip(this);
    }

    public void removeExpense(Expense expense) {
        expenses.remove(expense);
        expense.setTrip(null);
    }

    public void addSettlement(Settlement settlement) {
        settlements.add(settlement);
        settlement.setTrip(this);
    }

    public void removeSettlement(Settlement settlement) {
        settlements.remove(settlement);
        settlement.setTrip(null);
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Trip trip)) {
            return false;
        }
        return tripId != null && tripId.equals(trip.tripId);
    }

    @Override
    public int hashCode() {
        return Trip.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "Trip{tripId=" + tripId + ", tripName='" + tripName
                + "', status=" + status + "}";
    }
}
