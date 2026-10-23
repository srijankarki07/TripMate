package com.tripmate.ejb;

import java.util.List;

import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.entity.ItineraryItem;
import com.tripmate.entity.Trip;
import com.tripmate.entity.User;
import com.tripmate.remote.ItineraryServiceRemote;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The day-by-day plan for a trip.
 *
 * <p>Stateless with container-managed transactions.
 */
@Stateless
public class ItineraryService implements ItineraryServiceRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    @Override
    public ItineraryItemDTO addItem(Long tripId, ItineraryItemDTO item, Long authorId) {
        require(tripId, "tripId");
        require(item, "item");
        require(item.getTitle(), "title");
        require(item.getItemDate(), "itemDate");

        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }
        // The author must be on the trip. This method previously only checked
        // that the author existed, so anyone signed in could add an item to
        // anyone else's trip.
        TripAccess.requireMember(em, tripId, authorId, "add to this trip's itinerary");

        ItineraryItem entity = new ItineraryItem();
        entity.setTrip(trip);
        entity.setTitle(item.getTitle().trim());
        entity.setDescription(item.getDescription());
        entity.setItemDate(item.getItemDate());
        entity.setStartTime(item.getStartTime());
        entity.setEndTime(item.getEndTime());
        entity.setLocation(item.getLocation());

        if (authorId != null) {
            User author = em.find(User.class, authorId);
            if (author == null) {
                throw new ValidationException("no such user: " + authorId);
            }
            entity.setCreatedBy(author);
        }

        em.persist(entity);
        em.flush();
        return ItineraryItemDTO.from(entity);
    }

    @Override
    public ItineraryItemDTO getItem(Long itemId) {
        if (itemId == null) {
            return null;
        }
        return ItineraryItemDTO.from(em.find(ItineraryItem.class, itemId));
    }

    @Override
    public List<ItineraryItemDTO> getItineraryForTrip(Long tripId) {
        if (tripId == null) {
            return List.of();
        }
        // QUERY 1 (ROADMAP section 9.1). Navigates ItineraryItem to Trip, which
        // is the "related entity" the assignment asks this query to reach.
        //
        // createdBy is fetch-joined so the author's name resolves without a
        // query per item. The schedule columns are DATE and TIME rather than
        // TIMESTAMP precisely so this ordering needs no truncation expression.
        return em.createQuery(
                        "SELECT i FROM ItineraryItem i "
                                + "LEFT JOIN FETCH i.createdBy "
                                + "WHERE i.trip.tripId = :tripId "
                                + "ORDER BY i.itemDate ASC, i.startTime ASC",
                        ItineraryItem.class)
                .setParameter("tripId", tripId)
                .getResultList()
                .stream()
                .map(ItineraryItemDTO::from)
                .toList();
    }

    @Override
    public ItineraryItemDTO updateItem(ItineraryItemDTO item, Long actedBy) {
        require(item, "item");
        require(item.getItemId(), "itemId");

        ItineraryItem entity = em.find(ItineraryItem.class, item.getItemId());
        if (entity == null) {
            throw new ValidationException("no such itinerary item: " + item.getItemId());
        }
        // Checked against the item's own trip, which is the only way to know
        // which trip this is about: the DTO deliberately carries no trip id.
        TripAccess.requireMember(em, entity.getTrip().getTripId(), actedBy,
                "edit this trip's itinerary");

        if (item.getTitle() != null) {
            entity.setTitle(item.getTitle().trim());
        }
        entity.setDescription(item.getDescription());
        if (item.getItemDate() != null) {
            entity.setItemDate(item.getItemDate());
        }
        entity.setStartTime(item.getStartTime());
        entity.setEndTime(item.getEndTime());
        entity.setLocation(item.getLocation());

        // The edit is recorded whether or not the values differ, and the
        // revision goes up by one every time. A calendar that has already read
        // the feed compares this number against the one it holds to decide
        // whether its copy of the event is out of date, so an edit that left
        // the number alone would be an edit subscribers never see.
        //
        // Saving without changing anything therefore also counts, which is
        // slightly noisy and is the right way round: telling a subscriber that
        // an event changed when it did not is a wasted refresh, and not telling
        // them when it did is a calendar that quietly disagrees with the plan.
        entity.recordEdit();

        em.flush();
        return ItineraryItemDTO.from(entity);
    }

    @Override
    public void deleteItem(Long itemId, Long actedBy) {
        require(itemId, "itemId");

        ItineraryItem entity = em.find(ItineraryItem.class, itemId);
        if (entity == null) {
            throw new ValidationException("no such itinerary item: " + itemId);
        }
        TripAccess.requireMember(em, entity.getTrip().getTripId(), actedBy,
                "edit this trip's itinerary");

        em.remove(entity);
        em.flush();
    }

    @Override
    public long getItemCountForTrip(Long tripId) {
        if (tripId == null) {
            return 0L;
        }
        return em.createQuery(
                        "SELECT COUNT(i) FROM ItineraryItem i WHERE i.trip.tripId = :tripId",
                        Long.class)
                .setParameter("tripId", tripId)
                .getSingleResult();
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new ValidationException(name + " is required");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new ValidationException(name + " is required");
        }
    }
}
