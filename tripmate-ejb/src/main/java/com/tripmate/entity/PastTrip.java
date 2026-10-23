package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.tripmate.geo.PlaceName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A trip somebody took that was never planned in this application.
 *
 * <p>Maps to {@code past_trips}. The application plans trips forwards; a map of
 * where somebody has been is mostly places that were never planned here and
 * never will be, and without this the map would only ever show the handful of
 * trips that happen to be in this database.
 *
 * <h2>Why this is not a row in {@code trips}</h2>
 *
 * <p>A trip here has members, an itinerary, expenses and balances, and every
 * query about trips is written on the assumption that those exist. A past trip
 * has none of them: it is a place, roughly when, and a note. One table with a
 * flag would mean every one of those queries carrying a condition to exclude the
 * rows that are not trips, and the first one anybody forgot would be a balance
 * sheet counting a holiday from 2019.
 *
 * <p>{@link Trip} carries the matching argument for its own coordinates.
 */
@Entity
@Table(name = "past_trips")
public class PastTrip implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pastTripSeq")
    @SequenceGenerator(
            name = "pastTripSeq",
            sequenceName = "past_trips_past_trip_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "past_trip_id", nullable = false)
    private Long pastTripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "place_name", nullable = false, length = 150)
    private String placeName;

    @Column(name = "country", length = 100)
    private String country;

    /**
     * Roughly when, and deliberately allowed to be nothing.
     *
     * <p>Somebody adding a trip from years ago remembers a summer or a year, and
     * asking them to invent a day would make the record less true rather than
     * more. The map needs a place, which is why that is required and this is not.
     */
    @Column(name = "visited_on")
    private LocalDate visitedOn;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getPastTripId() {
        return pastTripId;
    }

    public void setPastTripId(Long pastTripId) {
        this.pastTripId = pastTripId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getPlaceName() {
        return placeName;
    }

    public void setPlaceName(String placeName) {
        this.placeName = placeName;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getVisitedOn() {
        return visitedOn;
    }

    public void setVisitedOn(LocalDate visitedOn) {
        this.visitedOn = visitedOn;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    /**
     * Places this record. Both axes together, because the column refuses either
     * one alone and a caller that set one would fail at flush rather than at the
     * line that caused it.
     */
    public void place(Double latitude, Double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
        if (!(other instanceof PastTrip trip)) {
            return false;
        }
        return pastTripId != null && pastTripId.equals(trip.pastTripId);
    }

    @Override
    public int hashCode() {
        return PastTrip.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "PastTrip{pastTripId=" + pastTripId + ", placeName='" + placeName + "'}";
    }
}
