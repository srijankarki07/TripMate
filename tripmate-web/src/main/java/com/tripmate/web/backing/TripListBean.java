package com.tripmate.web.backing;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

import com.tripmate.dto.TripDTO;
import com.tripmate.entity.TripStatus;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The list of trips the signed-in user belongs to, and the form that adds one.
 *
 * <p>Only their own trips. {@code TripService.getAllTrips} exists for
 * administrative views, but showing every trip in the system to every user
 * would be a different product.
 *
 * <p>Creating a trip is a dialog on this page rather than a page of its own.
 * The form is five fields, and a reader who has just decided to add one should
 * not lose the list they were reading to fill them in. The creator becomes the
 * trip's administrator, which the service does in the same transaction as the
 * trip itself, so there is no membership step.
 */
@Named
@ViewScoped
public class TripListBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private List<TripDTO> trips = List.of();

    private TripDTO newTrip = new TripDTO();
    private boolean dialogOpen;
    private String dialogError;

    /** Loads the list. Called from the view, because a view-scoped bean's
     *  post-construct runs before the session is necessarily readable here. */
    @jakarta.annotation.PostConstruct
    void load() {
        refresh();
    }

    private void refresh() {
        if (currentUser.isLoggedIn()) {
            trips = tripService.getTripsForUser(currentUser.getUserId());
        }
    }

    /** Set by the {@code new} query parameter, from the dashboard's button. */
    private String openRequested;

    /**
     * Opens the dialog when the page was reached by asking for it.
     *
     * <p>Driven by {@code f:viewAction} rather than post-construct, because at
     * post-construct time the query parameter has not been read yet.
     */
    public void applyOpenRequest() {
        if (openRequested != null && !openRequested.isBlank()) {
            openNewTrip();
        }
    }

    public String getOpenRequested() {
        return openRequested;
    }

    public void setOpenRequested(String openRequested) {
        this.openRequested = openRequested;
    }

    /** Opens the dialog on an empty form. */
    public void openNewTrip() {
        newTrip = new TripDTO();
        newTrip.setStatus(TripStatus.PLANNING);
        dialogError = null;
        dialogOpen = true;
    }

    public void closeNewTrip() {
        newTrip = new TripDTO();
        dialogError = null;
        dialogOpen = false;
    }

    /**
     * Creates the trip in the dialog and opens it.
     *
     * <p>A refusal keeps the dialog open with the fields as they were. Going
     * through to the new trip is the one place worth leaving this page for:
     * the trip exists now, and the next thing done to it is done on its own
     * page.
     *
     * @return a redirect to the new trip, or null to stay on the list
     */
    public String createTrip() {
        dialogError = null;

        if (newTrip.getTripName() == null || newTrip.getTripName().isBlank()) {
            dialogError = "Give the trip a name.";
            return null;
        }
        // Checked here as well as by the database constraint, so the reader
        // gets a sentence rather than a constraint violation.
        if (newTrip.getStartDate() != null && newTrip.getEndDate() != null
                && newTrip.getEndDate().isBefore(newTrip.getStartDate())) {
            dialogError = "The end date cannot be before the start date.";
            return null;
        }

        try {
            TripDTO created = tripService.createTrip(newTrip, currentUser.getUserId());
            Toasts.success(created.getTripName() + " is ready. Add the people who are going.");
            return "/trips/detail.xhtml?faces-redirect=true&tripId=" + created.getTripId();
        } catch (RuntimeException ex) {
            dialogError = Messages.describe(ex);
            return null;
        }
    }

    public List<TripDTO> getTrips() {
        return trips;
    }

    public boolean isHasTrips() {
        return !trips.isEmpty();
    }

    public int getUpcomingCount() {
        return (int) trips.stream()
                .filter(t -> t.getStatus().isLive())
                .count();
    }

    public TripDTO getNewTrip() {
        return newTrip;
    }

    public boolean isDialogOpen() {
        return dialogOpen;
    }

    /** @return why the last save was refused, or null */
    public String getDialogError() {
        return dialogError;
    }

    public List<TripStatus> getStatuses() {
        return List.of(TripStatus.values());
    }

    /** Unused today, kept so the dialog and the entity agree on the shape. */
    public boolean isStartAfterEnd() {
        return newTrip.getStartDate() != null && newTrip.getEndDate() != null
                && newTrip.getEndDate().isBefore(newTrip.getStartDate());
    }

    /** @return today, so a new trip's dates start somewhere sensible */
    public LocalDate getToday() {
        return LocalDate.now();
    }
}
