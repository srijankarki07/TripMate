package com.tripmate.web.backing;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

import com.tripmate.dto.TripDTO;
import com.tripmate.entity.TripStatus;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Creating a trip.
 *
 * <p>The creator becomes the trip's administrator, which the service does in the
 * same transaction as the trip itself, so the form has no membership step.
 */
@Named
@RequestScoped
public class TripCreateBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private String tripName;
    private String destination;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    private TripStatus status = TripStatus.PLANNING;

    private String error;

    /**
     * @return a redirect to the new trip, or null to redisplay the form
     */
    public String create() {
        error = null;

        if (tripName == null || tripName.isBlank()) {
            error = "Give the trip a name.";
            return null;
        }
        // Checked here as well as by the database constraint, so the user gets
        // a sentence rather than a constraint violation.
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            error = "The end date cannot be before the start date.";
            return null;
        }

        try {
            TripDTO created = tripService.createTrip(
                    new TripDTO(null, tripName.trim(), blankToNull(destination),
                            blankToNull(description), startDate, endDate,
                            status, null, 0),
                    currentUser.getUserId());

            return "/trips/detail.xhtml?faces-redirect=true&tripId=" + created.getTripId();

        } catch (RuntimeException ex) {
            error = rootMessage(ex);
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? "Could not create the trip." : message;
    }

    /** Drives the status picker. */
    public List<TripStatus> getStatuses() {
        return List.of(TripStatus.values());
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

    public String getError() {
        return error;
    }
}
