package com.tripmate.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import com.tripmate.entity.Trip;
import com.tripmate.entity.TripStatus;

/**
 * A trip, summarised for lists and headers.
 *
 * <p>This is a summary, not the whole trip: it carries {@code adminName} and
 * {@code memberCount} instead of the member list, so a dashboard listing twenty
 * trips is one query rather than twenty. It also carries no expense or
 * itinerary data; those have their own DTOs.
 *
 * <p>{@code description} is an addition to the field list in ROADMAP section 7,
 * which stops at {@code memberCount}. The column exists, the seed data
 * populates it, and the trip detail page has to display it, so leaving it out
 * would make the field writable only by hand in SQL.
 *
 * <p>{@link TripStatus} is reused from the entity package rather than
 * duplicated here. It is a domain value, not a persistence detail: it has no
 * JPA annotations, and copying it would create two enums that must be kept in
 * step for no benefit.
 */
public class TripDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;
    private String tripName;
    private String destination;
    private String description;
    private String timezone;
    private LocalDate startDate;
    private LocalDate endDate;
    private TripStatus status;
    private String adminName;
    private int memberCount;

    public TripDTO() {
    }

    public TripDTO(Long tripId, String tripName, String destination, String description,
                   String timezone, LocalDate startDate, LocalDate endDate, TripStatus status,
                   String adminName, int memberCount) {
        this.tripId = tripId;
        this.tripName = tripName;
        this.destination = destination;
        this.description = description;
        this.timezone = timezone;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.adminName = adminName;
        this.memberCount = memberCount;
    }

    /**
     * Maps an entity to its DTO using values the caller already has.
     *
     * <p>Preferred when mapping a list. The caller can obtain every name and
     * count in the same query that fetched the trips, which keeps the mapper
     * from issuing one query per row.
     */
    public static TripDTO from(Trip trip, String adminName, int memberCount) {
        if (trip == null) {
            return null;
        }
        return new TripDTO(
                trip.getTripId(),
                trip.getTripName(),
                trip.getDestination(),
                trip.getDescription(),
                trip.getTimezone(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getStatus(),
                adminName,
                memberCount);
    }

    /**
     * Maps an entity to its DTO by reading the associations.
     *
     * <p>Convenient for a single trip that has already been loaded, but it
     * traverses two lazy associations: resolving the administrator issues a
     * query, and counting members loads the entire member collection just to
     * call {@code size()}. Calling this in a loop over a list of trips produces
     * the classic N+1 pattern, so prefer the explicit overload there.
     */
    public static TripDTO from(Trip trip) {
        if (trip == null) {
            return null;
        }
        var admin = trip.getCreatedBy();
        return from(trip,
                admin == null ? null : admin.getFullName(),
                trip.getMembers().size());
    }

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * The zone this trip's wall-clock times are in, as an IANA id, or null.
     *
     * <p>Null is not "UTC"; it means nobody has said, and a reader of the times
     * should fall back the way {@link com.tripmate.entity.Trip#effectiveZone()}
     * does. Nothing outside the entity decides this for itself.
     */
    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
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

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TripDTO dto)) {
            return false;
        }
        return tripId != null && tripId.equals(dto.tripId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(tripId);
    }

    @Override
    public String toString() {
        return "TripDTO{tripId=" + tripId + ", tripName='" + tripName
                + "', status=" + status + ", memberCount=" + memberCount + "}";
    }
}
