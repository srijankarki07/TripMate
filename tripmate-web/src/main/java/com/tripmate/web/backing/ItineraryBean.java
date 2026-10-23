package com.tripmate.web.backing;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

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
    private List<ItineraryItemDTO> items = new ArrayList<>();

    /** Bound to the form fields. */
    private ItineraryItemDTO form = new ItineraryItemDTO();

    /** Null while adding; the item's id while editing it. */
    private Long editingItemId;

    private boolean member;
    public void load() {
        loadError = null;
        if (tripId == null) {
            loadError = "No trip was named.";
            return;
        }
        trip = tripService.getTrip(tripId);
        if (trip == null) {
            loadError = "That trip does not exist, or it has been deleted.";
            return;
        }
        member = tripService.isMember(tripId, currentUser.getUserId());
        if (!member) {
            loadError = "You are not a member of this trip.";
            trip = null;
            return;
        }
        refresh();
    }

    private void refresh() {
        items = new ArrayList<>(itineraryService.getItineraryForTrip(tripId));
    }

    /** @return a redirect back to the trip once saved, or null to stay */
    public String save() {

        if (form.getTitle() == null || form.getTitle().isBlank()) {
            Toasts.error("Give the item a title.");
            return null;
        }
        if (form.getItemDate() == null) {
            Toasts.error("Choose a day for this item.");
            return null;
        }
        // Checked here as well as by the database constraint, which spans the
        // two columns and would otherwise surface as a constraint violation.
        if (form.getStartTime() != null && form.getEndTime() != null
                && form.getEndTime().isBefore(form.getStartTime())) {
            Toasts.error("The end time cannot be before the start time.");
            return null;
        }

        try {
            if (editingItemId == null) {
                itineraryService.addItem(tripId, form, currentUser.getUserId());
            } else {
                form.setItemId(editingItemId);
                itineraryService.updateItem(form, currentUser.getUserId());
            }
            return "/trips/detail.xhtml?faces-redirect=true&tripId=" + tripId;

        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
            return null;
        }
    }

    /** Loads an item into the form for editing. */
    public void edit(Long itemId) {
        ItineraryItemDTO item = itineraryService.getItem(itemId);
        if (item == null) {
            Toasts.error("That item no longer exists.");
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
    }

    /** Loads a copy of an existing item into the form as a new item. */
    public void duplicate(Long itemId) {
        ItineraryItemDTO item = itineraryService.getItem(itemId);
        if (item == null) {
            Toasts.error("That item no longer exists.");
            return;
        }
        form = new ItineraryItemDTO(null, item.getTitle(), item.getDescription(),
                item.getItemDate(), item.getStartTime(), item.getEndTime(),
                item.getLocation(), null);
        editingItemId = null;
        Toasts.success("Editing a copy. Save to add it.");
    }

    public void delete(Long itemId) {
        try {
            itineraryService.deleteItem(itemId, currentUser.getUserId());
            Toasts.success("Item removed.");
            if (itemId.equals(editingItemId)) {
                cancelEdit();
            }
            refresh();
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
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

    /**
     * The list itself, deliberately, and mutable.
     *
     * <p>PrimeFaces sorts a table by sorting the collection this returns, in
     * place, and then reads it again to render. Returning a defensive copy
     * looks safer and silently breaks sorting: the sort lands on a copy that is
     * thrown away, and the render reads a fresh unsorted one. Returning one of
     * the immutable {@code List.of()} defaults instead throws
     * {@code UnsupportedOperationException} inside the render, which leaves the
     * table empty with nothing in the log to explain it.
     *
     * <p>So the field is a real {@code ArrayList} and is handed over as-is. The
     * sort mutating it is the intended contract, not an accident.
     *
     * @return the rows, which the table is expected to reorder
     */
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

    

    /**
     * Why the page could not be built, if it could not.
     *
     * <p>Kept apart from the toasts on purpose. A toast reports what
     * happened to an action and then leaves; this is a state the reader is
     * still in, so the page renders it in place with a way out.
     */
    private String loadError;

    /** @return the sentence explaining an unbuildable page, or null */
    public String getLoadError() {
        return loadError;
    }
}
