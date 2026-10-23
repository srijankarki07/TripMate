package com.tripmate.web.backing;

import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.dto.MapPointDTO;
import com.tripmate.dto.PastTripDTO;
import com.tripmate.geo.PlaceName;
import com.tripmate.remote.TravelMapRemote;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonWriter;

/**
 * The map page.
 *
 * <h2>Where things are is decided by clicking, not by guessing</h2>
 *
 * <p>Nothing in this application turns a place name into a coordinate. The
 * reader clicks where the trip was. That needs no geocoding service to be
 * reachable, works with the map they can already see, and is exact in a way no
 * lookup is: the person clicking was there.
 *
 * <p>Which means the page has three states, and the reader is always in one of
 * them: looking, about to place a trip, or about to place a new memory. The bean
 * holds which, because a half-finished placement is a state the page has to
 * render honestly rather than something that can live in the browser.
 *
 * <h2>Coordinates arrive from JavaScript</h2>
 *
 * <p>A click on the map is not a form submission, so the two numbers come back
 * through hidden fields that the page's script fills in before asking the server
 * to apply them. That is the ordinary PrimeFaces way and it keeps the values
 * typed: they are {@code Double} fields on this bean, not strings parsed by
 * hand, so a malformed one fails as a conversion error rather than as a
 * surprising coordinate.
 */
@Named
@ViewScoped
public class TravelMapBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(TravelMapBean.class.getName());

    @EJB
    private TravelMapRemote travelMap;

    @Inject
    private CurrentUser currentUser;

    /** Everything the map draws: the reader's trips and their own history. */
    private List<MapPointDTO> points = new ArrayList<>();

    /** The reader's recorded trips, newest first. */
    private List<PastTripDTO> pastTrips = new ArrayList<>();

    /** The trip being placed, or null when the reader is only looking. */
    private Long placingTripId;

    /** The dialog for a place the reader has been. */
    private boolean placeDialogOpen;

    /**
     * True when the reader has asked to type the name themselves after the
     * lookup filled it.
     *
     * <p>The name and country are shown as what they are, a description of
     * where the pin is, rather than as two boxes the reader is expected to fill
     * in. The lookup is usually right and occasionally wrong, so there has to be
     * a way to overrule it; this is that way, and it is deliberately one link
     * away rather than the default state of the form.
     */
    private boolean correctingPlaceName;

    /** Where the map was last clicked. Bound to hidden fields. */
    private Double clickLatitude;
    private Double clickLongitude;

    /** The form for a trip taken before this application existed. */
    private PastTripDTO newPlace = new PastTripDTO();

    /** Why the last thing the reader did was refused, or null. */
    private String error;

    @PostConstruct
    public void load() {
        refresh();
    }

    private void refresh() {
        try {
            points = new ArrayList<>(travelMap.pointsFor(currentUser.getUserId()));
            pastTrips = new ArrayList<>(travelMap.pastTripsFor(currentUser.getUserId()));
        } catch (RuntimeException ex) {
            error = Messages.describe(ex);
            points = new ArrayList<>();
            pastTrips = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // Placing
    // ------------------------------------------------------------------

    /**
     * Opens the dialog for a place the reader has been.
     *
     * <p>A dialog rather than a form on the page, which is what it was. The form
     * needed a map to pick on, the map needed room, and a reader half way
     * through describing a holiday from 2019 was doing it in a panel below
     * everything else with the pick happening somewhere off screen.
     */
    public void openAddPlace() {
        error = null;
        newPlace = new PastTripDTO();
        correctingPlaceName = false;
        placeDialogOpen = true;
    }

    public void closeAddPlace() {
        error = null;
        placeDialogOpen = false;
    }

    /**
     * Applies a spot the reader picked on the dialog's map.
     *
     * <p>The coordinates arrive on the DTO through the dialog's hidden fields.
     * What the place is called is asked of the lookup service, and it is
     * expected to fail sometimes: a reader whose machine cannot reach it types
     * the name, which is exactly what they did before this existed.
     *
     * <p>The extent that comes back with the name is kept on the DTO and stored
     * with the record, so the map fills the city rather than pinning a dot in it.
     */
    public void placePicked() {
        error = null;
        if (newPlace.getLatitude() == null || newPlace.getLongitude() == null) {
            return;
        }

        PlaceName found = null;
        try {
            found = travelMap.describe(newPlace.getLatitude(), newPlace.getLongitude());
        } catch (RuntimeException ex) {
            // Deliberately quiet. The reader has picked a spot; the name is a
            // convenience and a failure to name it is not a failure to place it.
            LOG.log(java.util.logging.Level.INFO, "Could not name a picked place", ex);
        }

        if (found == null) {
            error = "Picked. Type the name and country, then add it.";
            return;
        }

        /* Replaced, not filled only where empty.
         *
         * The first version kept whatever was already in the boxes, which meant
         * moving the pin to a different place left the previous place's name
         * sitting above it: the reader had to clear the fields by hand to make
         * the pin and the name agree. Whoever moves the pin means somewhere
         * else, so the name follows the pin, and an edit made afterwards is
         * kept until the pin moves again. */
        if (found.getName() != null) {
            newPlace.setPlaceName(found.getName());
        }
        if (found.getCountry() != null) {
            newPlace.setCountry(found.getCountry());
        }
        newPlace.setAreaGeojson(found.getGeometry());
        // A new spot means a new name, so the reader is shown the answer again
        // rather than left in the typing state from the previous one.
        correctingPlaceName = false;
    }

    /** Lets the reader type the name and country instead of the lookup's. */
    public void correctPlaceName() {
        correctingPlaceName = true;
    }

    /**
     * @return true when the name should be shown as text rather than as two
     *         boxes, which is whenever the lookup gave an answer and the reader
     *         has not asked to overrule it
     */
    public boolean isNameFromLookup() {
        return isNewPlacePlaced() && !correctingPlaceName;
    }

    /** @return true when the dialog's map has a spot, so the page can say so */
    public boolean isNewPlacePlaced() {
        return newPlace != null && newPlace.isPlaced();
    }

    public boolean isPlaceDialogOpen() {
        return placeDialogOpen;
    }

    /** Takes a trip off the map, leaving it otherwise untouched. */
    public void unplaceTrip(Long tripId) {
        error = null;
        try {
            travelMap.placeTrip(tripId, null, null, currentUser.getUserId());
            Toasts.success("Taken off the map. The trip itself is untouched.");
            refresh();
        } catch (RuntimeException ex) {
            error = Messages.describe(ex);
        }
    }

    /**
     * Applies a click on the main map.
     *
     * <p>Only one thing can be waiting, and it is always a trip: picking a place
     * the reader has been happens in the dialog, on its own map.
     */
    public void mapClicked() {
        error = null;
        if (clickLatitude == null || clickLongitude == null) {
            error = "That click did not carry a place. Try clicking the map again.";
            return;
        }
        if (placingTripId == null) {
            return;
        }
        try {
            travelMap.placeTrip(placingTripId, clickLatitude, clickLongitude,
                    currentUser.getUserId());
            Toasts.success("Trip placed on the map.");
            stopPlacing();
            refresh();
        } catch (RuntimeException ex) {
            error = Messages.describe(ex);
            stopPlacing();
        }
    }

    /** Arms the map so the next click places this trip. */
    public void startPlacing(Long tripId) {
        error = null;
        placingTripId = tripId;
    }

    /** Puts the map back to merely looking at it. */
    public void stopPlacing() {
        placingTripId = null;
        error = null;
    }

    /** @return true when a click on the map would place something */
    public boolean isPlacing() {
        return placingTripId != null;
    }

    // ------------------------------------------------------------------
    // The reader's own history
    // ------------------------------------------------------------------

    public void addPastTrip() {
        error = null;
        if (newPlace.getLatitude() == null || newPlace.getLongitude() == null) {
            error = "Pick the place on the map first.";
            return;
        }
        try {
            travelMap.addPastTrip(newPlace, currentUser.getUserId());
            Toasts.success("Added to your map.");
            newPlace = new PastTripDTO();
            placeDialogOpen = false;
            refresh();
        } catch (RuntimeException ex) {
            error = Messages.describe(ex);
        }
    }

    public void removePastTrip(Long pastTripId) {
        error = null;
        try {
            travelMap.removePastTrip(pastTripId, currentUser.getUserId());
            Toasts.success("Removed from your map.");
            refresh();
        } catch (RuntimeException ex) {
            error = Messages.describe(ex);
        }
    }

    // ------------------------------------------------------------------
    // What the page draws
    // ------------------------------------------------------------------

    /**
     * The points, as JSON for the map script.
     *
     * <p>Rendered into the page rather than fetched, because the map cannot draw
     * anything until it has them and a second round trip would show an empty
     * world first. The bean already has them; this is one serialisation.
     *
     * @return a JSON array, never null
     */
    public String getPointsJson() {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (MapPointDTO point : points) {
            if (!point.isPlaced()) {
                // Left out rather than sent with nulls: the script would have to
                // check, and a point with no place has nothing to draw.
                continue;
            }
            JsonObjectBuilder json = Json.createObjectBuilder()
                    .add("kind", point.getKind().name())
                    .add("id", point.getId())
                    .add("name", nullToEmpty(point.getName()))
                    .add("lat", point.getLatitude())
                    .add("lon", point.getLongitude());
            putIfPresent(json, "place", point.getPlace());
            putIfPresent(json, "status", point.getStatus());
            putIfPresent(json, "note", point.getNote());
            putIfPresent(json, "start", point.getStartDate() == null
                    ? null : point.getStartDate().toString());
            putIfPresent(json, "end", point.getEndDate() == null
                    ? null : point.getEndDate().toString());
            putIfPresent(json, "visited", point.getVisitedOn() == null
                    ? null : point.getVisitedOn().toString());
            if (point.isHasArea()) {
                // Read back from the text it is stored as, so the page receives a
                // JSON object rather than a string containing one. Sent as a
                // string, every quote in the boundary would be escaped in an HTML
                // attribute and a ten-kilobyte shape would cross the wire twice
                // the size, for the script to parse it back again.
                JsonObject geometry = geometryOf(point.getAreaGeojson());
                if (geometry != null) {
                    json.add("area", geometry);
                }
            }
            if (point.isTrip()) {
                json.add("items", point.getItemCount());
                json.add("members", point.getMemberCount());
                json.add("spent", point.getTotalSpent() == null
                        ? "0" : point.getTotalSpent().toPlainString());
            }
            array.add(json);
        }

        StringWriter out = new StringWriter();
        try (JsonWriter writer = Json.createWriter(out)) {
            writer.writeArray(array.build());
        }
        return out.toString();
    }

    /** @return the trips the reader has not put on the map yet, newest first */
    public List<MapPointDTO> getUnplacedTrips() {
        return points.stream().filter(p -> p.isTrip() && !p.isPlaced()).toList();
    }

    /** @return true when there is nothing at all to draw yet */
    public boolean isNothingYet() {
        return points.isEmpty() && pastTrips.isEmpty();
    }

    public boolean isHasUnplacedTrips() {
        return !getUnplacedTrips().isEmpty();
    }

    /** @return the trip being placed, for the page to name it */
    public String getPlacingName() {
        if (placingTripId == null) {
            return null;
        }
        for (MapPointDTO point : points) {
            if (point.isTrip() && placingTripId.equals(point.getId())) {
                return point.getName();
            }
        }
        return "this trip";
    }

    /**
     * The stored boundary, as JSON, or null when it cannot be read.
     *
     * <p>Null rather than an error: a shape that will not parse costs the
     * filled region and leaves the point drawn as it was before there were any,
     * which is not worth failing a page over.
     */
    private static JsonObject geometryOf(String stored) {
        try (JsonReader reader = Json.createReader(new StringReader(stored))) {
            return reader.readObject();
        } catch (RuntimeException malformed) {
            LOG.log(java.util.logging.Level.WARNING, "A stored boundary could not be read", malformed);
            return null;
        }
    }

    private static void putIfPresent(JsonObjectBuilder json, String name, String value) {
        if (value != null && !value.isBlank()) {
            json.add(name, value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public List<MapPointDTO> getPoints() {
        return points;
    }

    public List<PastTripDTO> getPastTrips() {
        return pastTrips;
    }

    public PastTripDTO getNewPlace() {
        return newPlace;
    }

    public Double getClickLatitude() {
        return clickLatitude;
    }

    public void setClickLatitude(Double clickLatitude) {
        this.clickLatitude = clickLatitude;
    }

    public Double getClickLongitude() {
        return clickLongitude;
    }

    public void setClickLongitude(Double clickLongitude) {
        this.clickLongitude = clickLongitude;
    }

    public Long getPlacingTripId() {
        return placingTripId;
    }

    public String getError() {
        return error;
    }

    /** @return the year the reader is most likely to want, for the date field */
    public LocalDate getToday() {
        return LocalDate.now();
    }
}
