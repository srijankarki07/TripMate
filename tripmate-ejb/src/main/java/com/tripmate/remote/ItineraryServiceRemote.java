package com.tripmate.remote;

import java.util.List;

import com.tripmate.dto.ItineraryItemDTO;

import jakarta.ejb.Remote;

/**
 * The day-by-day plan for a trip.
 *
 * <p>Items are always addressed in the context of their trip, which is why
 * {@link #addItem} takes the trip id as an argument rather than reading it from
 * the DTO. {@code ItineraryItemDTO} deliberately carries no trip id, so there is
 * no second copy of it that could disagree with the argument.
 */
@Remote
public interface ItineraryServiceRemote {

    /**
     * Adds an item to a trip's itinerary.
     *
     * @param tripId   the trip to add to
     * @param item     the item; {@code itemId} is ignored
     * @param authorId who is adding it, recorded for display
     * @return the stored item, with the generated id
     * @throws IllegalArgumentException if the trip or author does not exist, or
     *                                  the title or date is missing
     */
    ItineraryItemDTO addItem(Long tripId, ItineraryItemDTO item, Long authorId);

    /** @return the item, or {@code null} if no such id */
    ItineraryItemDTO getItem(Long itemId);

    /**
     * Query 1 (ROADMAP section 9.1): a trip's itinerary in the order it will
     * actually be experienced.
     *
     * <p>Ordered by date and then by start time, not by insertion order, so
     * items added out of sequence still read as a plan. Items with no start time
     * sort first within their day.
     *
     * @return the itinerary; empty if the trip has none
     */
    List<ItineraryItemDTO> getItineraryForTrip(Long tripId);

    /**
     * Updates an item's fields.
     *
     * @throws IllegalArgumentException if the item does not exist
     */
    ItineraryItemDTO updateItem(ItineraryItemDTO item, Long actedBy);

    /**
     * Deletes an item.
     *
     * @throws IllegalArgumentException if the item does not exist
     */
    void deleteItem(Long itemId, Long actedBy);

    /** @return how many items the trip has */
    long getItemCountForTrip(Long tripId);
}
