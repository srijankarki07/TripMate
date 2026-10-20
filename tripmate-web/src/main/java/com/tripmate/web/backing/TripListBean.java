package com.tripmate.web.backing;

import java.io.Serializable;
import java.util.List;

import com.tripmate.dto.TripDTO;
import com.tripmate.entity.TripStatus;
import com.tripmate.remote.TripServiceRemote;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The list of trips the signed-in user belongs to.
 *
 * <p>Only their own trips. {@code TripService.getAllTrips} exists for
 * administrative views, but showing every trip in the system to every user
 * would be a different product.
 */
@Named
@RequestScoped
public class TripListBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private List<TripDTO> trips = List.of();

    @PostConstruct
    void load() {
        if (currentUser.isLoggedIn()) {
            trips = tripService.getTripsForUser(currentUser.getUserId());
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
                .filter(t -> t.getStatus() == TripStatus.PLANNING || t.getStatus() == TripStatus.ONGOING)
                .count();
    }
}
