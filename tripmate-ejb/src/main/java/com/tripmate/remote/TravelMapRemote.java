package com.tripmate.remote;

import java.util.List;

import com.tripmate.dto.MapPointDTO;
import com.tripmate.dto.PastTripDTO;
import com.tripmate.geo.PlaceName;

import jakarta.ejb.Remote;

/**
 * Where a reader has been, and where they are going.
 *
 * <h2>Two records, one map</h2>
 *
 * <p>Everything here exists to fill one picture: the trips this application
 * knows about, placed by the reader, alongside the trips they took before they
 * used it and wrote down from memory. The two are stored apart, deliberately,
 * and {@link MapPointDTO} is where they meet.
 *
 * <p>The map is the reader's own. A trip appears on it if they are a member of
 * it, and a past trip appears if they recorded it. Nothing here is shared
 * between readers, and no method takes a destination user.
 */
@Remote
public interface TravelMapRemote {

    /**
     * Everything the reader's map should draw.
     *
     * <p>Includes trips that have never been placed, with null coordinates,
     * because the page has to offer to put them somewhere. A list of only the
     * placed ones would leave a reader with an empty map and no way to fill it.
     *
     * @param userId the reader
     * @return their trips, newest first, followed by their past trips
     * @throws IllegalArgumentException if the user does not exist
     */
    List<MapPointDTO> pointsFor(Long userId);

    /**
     * What is at a point: its name, its country, and its extent.
     *
     * <p>Asked when a reader picks a spot, so they are not made to type what
     * they have just clicked. Returns null rather than refusing when the
     * lookup service cannot be reached: a name is a convenience, and a reader
     * who has to type it is inconvenienced, not blocked.
     *
     * @param latitude  where
     * @param longitude where
     * @return what was found, or null
     */
    PlaceName describe(Double latitude, Double longitude);

    /**
     * Puts a trip on the map, or takes it off.
     *
     * <p>Administrators only, which is the same rule as editing the trip's
     * destination: where a trip is belongs to the trip and not to whoever
     * happens to be looking at it. Passing null coordinates removes it, which is
     * how a reader corrects a trip they placed in the wrong country.
     *
     * @param tripId    the trip to place
     * @param latitude  the latitude, or null to take the trip off the map
     * @param longitude the longitude, or null likewise
     * @param actedBy   who is asking; must administer the trip
     * @throws IllegalArgumentException if they do not, or the coordinates are
     *                                  not on the earth
     */
    void placeTrip(Long tripId, Double latitude, Double longitude, Long actedBy);

    /**
     * Records a trip the reader took before they used this application.
     *
     * @param trip    the place, when, and any note; the id is ignored
     * @param userId  whose history this belongs to
     * @return the stored record, with its generated id
     * @throws IllegalArgumentException if the place is unnamed or the
     *                                  coordinates are not on the earth
     */
    PastTripDTO addPastTrip(PastTripDTO trip, Long userId);

    /**
     * Forgets one.
     *
     * @param pastTripId the record to remove
     * @param userId     who is asking; must own it
     * @throws IllegalArgumentException if it does not exist, or is somebody
     *                                  else's
     */
    void removePastTrip(Long pastTripId, Long userId);

    /**
     * The reader's past trips, newest first.
     *
     * @param userId the reader
     * @return their records; empty if they have none
     */
    List<PastTripDTO> pastTripsFor(Long userId);
}
