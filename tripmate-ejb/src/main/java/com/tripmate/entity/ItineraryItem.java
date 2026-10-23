package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * One scheduled activity within a trip.
 *
 * <p>Maps to the {@code itinerary_items} table. The schedule is stored as
 * {@link LocalDate} plus two {@link LocalTime}s rather than a pair of
 * timestamps, so query 1 (ROADMAP §9.1) can order by day and then by start
 * time without a truncation expression.
 */
@Entity
@Table(name = "itinerary_items")
public class ItineraryItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "itineraryItemSeq")
    @SequenceGenerator(
            name = "itineraryItemSeq",
            sequenceName = "itinerary_items_item_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "item_date", nullable = false)
    private LocalDate itemDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "location", length = 200)
    private String location;

    /**
     * Nullable, and mapped {@code optional = true} to match. The schema uses
     * {@code ON DELETE SET NULL}: removing a user must not delete the itinerary
     * item they happened to add.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * How many times this item has been edited, and when it last was.
     *
     * <p>Both are written into the calendar feed: the revision as the event's
     * {@code SEQUENCE} and the time as its {@code LAST-MODIFIED}. A calendar
     * application that has already read the feed uses those two to decide
     * whether it has a newer copy of an event, so they are what make an edit
     * show up in a subscriber's calendar rather than only in this application.
     *
     * <p>{@link #createdAt} cannot serve that purpose: it is set once and never
     * moves, so an edited item would be indistinguishable from one the reader
     * had already seen.
     */
    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Records an edit.
     *
     * <p>A method rather than two setters because the two must move together,
     * and because the revision only ever goes up. A caller that could set a
     * revision could set a lower one, and a {@code SEQUENCE} that has gone
     * backwards is an event a calendar will refuse to update.
     *
     * <p>Called by the service on every write. The entity manager does not bump
     * it by itself: JPA has no notion of a version the application also
     * publishes, and {@code @Version} is for optimistic locking, which is a
     * different problem with a different consequence if it fails.
     */
    public void recordEdit() {
        revision++;
        updatedAt = LocalDateTime.now();
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getItemDate() {
        return itemDate;
    }

    public void setItemDate(LocalDate itemDate) {
        this.itemDate = itemDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
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

    /** @return how many times this item has been edited; never negative */
    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /** @return when this item was last written, which is when it was added if never edited */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItineraryItem item)) {
            return false;
        }
        return itemId != null && itemId.equals(item.itemId);
    }

    @Override
    public int hashCode() {
        return ItineraryItem.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "ItineraryItem{itemId=" + itemId + ", title='" + title
                + "', itemDate=" + itemDate + "}";
    }
}
