package com.tripmate.web.backing;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.List;

import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Adding, editing and removing the items on a trip's itinerary.
 *
 * <p>One form serves both adding and editing. {@link #editingItemId} being null
 * means the form is adding; anything else means it is editing that item. Two
 * separate forms would duplicate every field and every validation message.
 */
@Named
@ViewScoped
public class ItineraryBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private ItineraryServiceRemote itineraryService;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private Long tripId;
    private TripDTO trip;
    private List<ItineraryItemDTO> items = List.of();

    /** Bound to the form fields. */
    private ItineraryItemDTO form = new ItineraryItemDTO();

    /** Null while adding; the item's id while editing it. */
    private Long editingItemId;

    private boolean member;
    private String error;
    private String message;

    public void load() {
        error = null;
        if (tripId == null) {
            error = "No trip was named.";
            return;
        }
        trip = tripService.getTrip(tripId);
        if (trip == null) {
            error = "That trip does not exist, or it has been deleted.";
            return;
        }
        member = tripService.isMember(tripId, currentUser.getUserId());
        if (!member) {
            error = "You are not a member of this trip.";
            trip = null;
            return;
        }
        refresh();
    }

    private void refresh() {
        items = itineraryService.getItineraryForTrip(tripId);
    }

    /** @return a redirect back to the trip once saved, or null to stay */
    public String save() {
        error = null;
        message = null;

        if (form.getTitle() == null || form.getTitle().isBlank()) {
            error = "Give the item a title.";
            return null;
        }
        if (form.getItemDate() == null) {
            error = "Choose a day for this item.";
            return null;
        }
        // Checked here as well as by the database constraint, which spans the
        // two columns and would otherwise surface as a constraint violation.
        if (form.getStartTime() != null && form.getEndTime() != null
                && form.getEndTime().isBefore(form.getStartTime())) {
            error = "The end time cannot be before the start time.";
            return null;
        }

        try {
            if (editingItemId == null) {
                itineraryService.addItem(tripId, form, currentUser.getUserId());
            } else {
                form.setItemId(editingItemId);
                itineraryService.updateItem(form);
            }
            return "/trips/detail.xhtml?faces-redirect=true&tripId=" + tripId;

        } catch (RuntimeException ex) {
            error = rootMessage(ex);
            return null;
        }
    }

    /** Loads an item into the form for editing. */
    public void edit(Long itemId) {
        error = null;
        message = null;
        ItineraryItemDTO item = itineraryService.getItem(itemId);
        if (item == null) {
            error = "That item no longer exists.";
            refresh();
            return;
        }
        form = item;
        editingItemId = itemId;
    }

    /** Abandons an edit and returns the form to adding. */
    public void cancelEdit() {
        form = new ItineraryItemDTO();
        editingItemId = null;
        error = null;
    }

    /** Loads a copy of an existing item into the form as a new item. */
    public void duplicate(Long itemId) {
        ItineraryItemDTO item = itineraryService.getItem(itemId);
        if (item == null) {
            error = "That item no longer exists.";
            return;
        }
        form = new ItineraryItemDTO(null, item.getTitle(), item.getDescription(),
                item.getItemDate(), item.getStartTime(), item.getEndTime(),
                item.getLocation(), null);
        editingItemId = null;
        error = null;
        message = "Editing a copy. Save to add it.";
    }

    public void delete(Long itemId) {
        error = null;
        message = null;
        try {
            itineraryService.deleteItem(itemId);
            message = "Item removed.";
            if (itemId.equals(editingItemId)) {
                cancelEdit();
            }
            refresh();
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    /** Quick times for the picker, so the common cases need no typing. */
    public List<LocalTime> getQuarterHours() {
        List<LocalTime> times = new java.util.ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(LocalTime.of(hour, 0));
            times.add(LocalTime.of(hour, 15));
            times.add(LocalTime.of(hour, 30));
            times.add(LocalTime.of(hour, 45));
        }
        return times;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public TripDTO getTrip() {
        return trip;
    }

    public List<ItineraryItemDTO> getItems() {
        return items;
    }

    public ItineraryItemDTO getForm() {
        return form;
    }

    public void setForm(ItineraryItemDTO form) {
        this.form = form;
    }

    public Long getEditingItemId() {
        return editingItemId;
    }

    public boolean isEditing() {
        return editingItemId != null;
    }

    public boolean isMember() {
        return member;
    }

    public boolean isLoaded() {
        return trip != null;
    }

    public boolean isHasItems() {
        return !items.isEmpty();
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? "Something went wrong." : message;
    }
}
