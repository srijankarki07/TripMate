package com.tripmate.dto;

import java.io.Serializable;
import java.time.LocalDate;

import com.tripmate.entity.PastTrip;

/**
 * A trip somebody took that was never planned here.
 *
 * <p>Carries the owner's id rather than a name, unlike most of the DTOs in this
 * package. Those drop the owner because the page already knows whose trip it is
 * looking at; a past trip is a personal record and the write operations take the
 * reader as a separate argument, so repeating them here would be a second place
 * the two could disagree about who owns the row.
 */
public class PastTripDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long pastTripId;
    private String placeName;
    private String country;
    private LocalDate visitedOn;
    private String note;
    private Double latitude;
    private Double longitude;

    // The boundary of the place as GeoJSON text, or null. Null means the map
    // draws a dot; otherwise it fills the shape.
    private String areaGeojson;


    public PastTripDTO() {
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>Traverses {@code trip.user} only to prove it is there; the id is not
     * read, because the caller already knows it.
     */
    public static PastTripDTO from(PastTrip trip) {
        if (trip == null) {
            return null;
        }
        PastTripDTO dto = new PastTripDTO();
        dto.pastTripId = trip.getPastTripId();
        dto.placeName = trip.getPlaceName();
        dto.country = trip.getCountry();
        dto.visitedOn = trip.getVisitedOn();
        dto.note = trip.getNote();
        dto.latitude = trip.getLatitude();
        dto.longitude = trip.getLongitude();
        dto.areaGeojson = trip.getAreaGeojson();
        return dto;
    }

    /** @return true when this record has a place on the map */
    public boolean isPlaced() {
        return latitude != null && longitude != null;
    }

    public Long getPastTripId() {
        return pastTripId;
    }

    public void setPastTripId(Long pastTripId) {
        this.pastTripId = pastTripId;
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
