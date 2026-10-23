package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One thing to draw on the map.
 *
 * <h2>Two kinds in one list</h2>
 *
 * <p>A map point is either a trip this application knows about or a trip
 * somebody remembered and wrote down. They are different records in different
 * tables with almost nothing in common, and they share this shape because a map
 * needs one list, and because the alternative is the page merging two lists in
 * JavaScript and getting the ordering wrong.
 *
 * <p>{@code kind} is what tells them apart, and the page draws them differently
 * from it rather than from whichever fields happen to be null. A past trip has
 * no status, no members and no money, so those are null or zero here, and the
 * page must not treat "no status" as a status.
 *
 * <h2>Coordinates may be null</h2>
 *
 * <p>A trip that has never been placed comes back with null latitude and
 * longitude rather than being left out, because the page has to show the reader
 * the trips that are not on the map yet and offer to put them there. Filtering
 * them out here would leave the reader looking at a map with no way to fill it.
 */
public class MapPointDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Which of the two records this is. */
    public enum Kind {
        /** A trip planned in this application. */
        TRIP,
        /** A place somebody recorded from before they used it. */
        PAST_TRIP
    }

    private Kind kind;
    private Long id;
    private String name;

    /** The destination for a trip, the country for a past trip, or null. */
    private String place;

    /** The lifecycle state of a trip. Null for a past trip, which has none. */
    private String status;

    /** When a trip runs. Null for a past trip. */
    private LocalDate startDate;
    private LocalDate endDate;

    /** When a past trip was taken. Null for a trip, and null for an undated past trip. */
    private LocalDate visitedOn;

    /** The trip's own description, or the note somebody wrote about a past trip. */
    private String note;

    /** How many items are planned. Zero for a past trip. */
    private int itemCount;

    /** How many people are going. Zero for a past trip. */
    private int memberCount;

    /** What the trip has cost so far. Zero for a past trip, which has no expenses. */
    private BigDecimal totalSpent;

    /**
     * Whether the reader asking may place this trip.
     *
     * <p>False for a past trip, which is the reader's own and needs no
     * permission, and for a trip they are only a member of: where a trip is
     * belongs to the trip, which is the same rule as editing its destination.
     *
     * <p>Carried so the page offers the control exactly when the service would
     * accept it. The service refuses anyway, because hiding a control is a
     * courtesy and not a guard.
     */
    private boolean canPlace;

    // Null until the thing has been placed on the map.
    private Double latitude;
    private Double longitude;

    // The boundary of the place as GeoJSON text, or null. Null means the map
    // draws a dot; otherwise it fills the shape.
    private String areaGeojson;


    public MapPointDTO() {
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    /** @return true when this is a trip planned in this application */
    public boolean isTrip() {
        return kind == Kind.TRIP;
    }

    /** @return true when this record has a place on the map */
    public boolean isPlaced() {
        return latitude != null && longitude != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlace() {
        return place;
    }

    public void setPlace(String place) {
        this.place = place;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    public BigDecimal getTotalSpent() {
        return totalSpent;
    }

    public void setTotalSpent(BigDecimal totalSpent) {
        this.totalSpent = totalSpent;
    }

    public boolean isCanPlace() {
        return canPlace;
    }

    public void setCanPlace(boolean canPlace) {
        this.canPlace = canPlace;
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

    /** @return the boundary as GeoJSON text, or null */
    public String getAreaGeojson() {
        return areaGeojson;
    }

    public void setAreaGeojson(String areaGeojson) {
        this.areaGeojson = areaGeojson;
    }

    /** @return true when the map should fill the shape rather than pin a dot */
    public boolean isHasArea() {
        return areaGeojson != null && !areaGeojson.isBlank();
    }
}
