package com.tripmate.entity;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.geo.PlaceName;

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

    /**
     * The zone this trip's wall-clock times are in, as an IANA id, or null when
     * nobody has said.
     *
     * <p>An itinerary item is a date and two local times. That is the right
     * thing to store, and it is not enough to place the item on a timeline:
     * 07:00 is a different moment in Kathmandu than in London. Anything that has
     * to write one of those times somewhere outside this application needs the
     * zone, and the calendar feed is where that became visible.
     *
     * <p>Null means the server's own zone, which is right for a trip planned
     * where the server is. {@link #effectiveZone()} is the one place that
     * decides, so nothing else has to repeat the fallback.
     */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /**
     * Where the trip is, for the map. Null until somebody places it.
     *
     * <p>The {@code destination} above is free text a person typed, and nothing
     * in this application turns text into a coordinate. The reader places a trip
     * by clicking where it was, which needs no geocoding service and is exact,
     * because the person doing it was there.
     *
     * <p>Kept out of {@code TripDTO} deliberately. Editing a trip's name or
     * dates goes through that DTO, and a field it did not carry is a field that
     * cannot be cleared by saving a form that never knew about it.
     */
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

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

    /** @return the zone as stored, or null when the trip has never been given one */
    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    /** @return true when this trip has been placed on the map */
    public boolean isPlaced() {
        return latitude != null && longitude != null;
    }

    /**
     * Places the trip, or takes it off the map when given nothing.
     *
     * <p>Both axes together, because the schema refuses half a coordinate and a
     * caller that set one and not the other would fail at flush with a
     * constraint violation rather than at the line that caused it.
     *
     * @param latitude  the latitude, or null to remove the trip from the map
     * @param longitude the longitude, or null likewise
     */
    public void place(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            this.latitude = null;
            this.longitude = null;
            return;
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /**
     * The zone to read this trip's wall-clock times in.
     *
     * <p>Falls back to the server's own zone when the trip has none, and to UTC
     * if even the stored id is one this runtime does not know, which would mean
     * a zone renamed out from under a trip that named it. Neither fallback is
     * silent: both are cases where the alternative is refusing to render a plan
     * at all.
     *
     * @return a zone, never null
     */
    public ZoneId effectiveZone() {
        if (timezone != null && !timezone.isBlank()) {
            try {
                return ZoneId.of(timezone.trim());
            } catch (DateTimeException unknownZone) {
                // A trip naming a zone this runtime cannot resolve. UTC is a
                // real answer rather than a refusal; the alternative is a plan
                // that cannot be read at all.
            }
        }
        return ZoneId.systemDefault();
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
    // The extent of the place
    //
    // Filled from the same lookup that names it, and used only to draw it. A
    // bounding box is four numbers and not a boundary, which is why it is
    // stored as four numbers: giving it a shape that suggests geography would
    // invite somebody to treat it as geography.
    // ------------------------------------------------------------------

    @Column(name = "area_geojson", columnDefinition = "text")
    private String areaGeojson;

    /**
     * Records what the place turned out to be.
     *
     * <p>Takes the whole lookup rather than four numbers, so a caller cannot
     * pass the axes in the wrong order. The order they arrive in from the
     * source is south, north, west, east, which is not the order anybody would
     * guess.
     *
     * @param place what was found, or null to forget the extent
     */
    public void setArea(PlaceName place) {
        this.areaGeojson = place == null ? null : place.getGeometry();
    }

    /** @return true when there is a boundary to draw */
    public boolean hasArea() {
        return areaGeojson != null && !areaGeojson.isBlank();
    }

    /** @return the boundary as GeoJSON text, or null */
    public String getAreaGeojson() {
        return areaGeojson;
    }

    public void setAreaGeojson(String areaGeojson) {
        this.areaGeojson = areaGeojson;
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
