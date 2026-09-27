package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.dto.UserDTO;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.TripStatus;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.remote.UserServiceRemote;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * One trip: its itinerary, expenses, balances and members.
 *
 * <p>View-scoped, because the page is a small editor: an edit to the name and
 * an edit to the dates are the same conversation. It also means the four tabs
 * read from data loaded once rather than re-querying on every tab switch.
 *
 * <p>The trip id arrives as a view parameter and is applied before
 * {@code load()} runs, which is why loading is driven by {@code f:viewAction}
 * rather than {@code @PostConstruct}: at post-construct time the parameter has
 * not been set yet.
 */
@Named
@ViewScoped
public class TripDetailBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private TripServiceRemote tripService;

    @EJB
    private ItineraryServiceRemote itineraryService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private ReportServiceRemote reportService;

    @EJB
    private UserServiceRemote userService;

    @Inject
    private CurrentUser currentUser;

    private Long tripId;

    private TripDTO trip;
    private List<TripMemberDTO> members = List.of();
    private List<ItineraryItemDTO> itinerary = List.of();
    private List<ExpenseDTO> expenses = List.of();
    private List<MemberBalanceDTO> balances = List.of();
    private List<SettlementDTO> settlements = List.of();

    private Long newMemberId;
    private boolean member;
    private boolean admin;
    private String error;
    private String message;

    /** Loads everything the page shows. Called after the view parameter is set. */
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

        Long userId = currentUser.getUserId();
        TripRole role = tripService.getMemberRole(tripId, userId);
        member = role != null;
        admin = role == TripRole.ADMIN;

        // The filter guarantees a signed-in user but not that they belong to
        // this trip. A non-member sees nothing rather than a partly loaded page.
        if (!member) {
            error = "You are not a member of this trip.";
            trip = null;
            return;
        }

        members = tripService.getTripMembers(tripId);
        itinerary = itineraryService.getItineraryForTrip(tripId);
        expenses = expenseService.getExpensesForTrip(tripId);
        balances = reportService.getMemberBalances(tripId);
        settlements = expenseService.getSettlementsForTrip(tripId);
    }

    // ------------------------------------------------------------------
    // Editing the trip
    // ------------------------------------------------------------------

    public void save() {
        error = null;
        message = null;
        try {
            trip = tripService.updateTrip(trip, currentUser.getUserId());
            message = "Trip saved.";
            // The header shows the name and dates, which are read from the
            // refreshed DTO, so there is nothing else to reload for those.
            members = tripService.getTripMembers(tripId);
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    /** @return a redirect to the trip list once the trip is gone */
    public String delete() {
        error = null;
        try {
            tripService.deleteTrip(tripId, currentUser.getUserId());
            return "/trips/list.xhtml?faces-redirect=true";
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    public void addMember() {
        error = null;
        message = null;
        if (newMemberId == null) {
            error = "Choose someone to add.";
            return;
        }
        try {
            UserDTO user = userService.getUser(newMemberId);
            tripService.addMember(tripId, newMemberId, TripRole.MEMBER, currentUser.getUserId());
            message = (user == null ? "Member" : user.getFullName()) + " joined the trip.";
            newMemberId = null;
            members = tripService.getTripMembers(tripId);
            trip = tripService.getTrip(tripId);
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    public void removeMember(Long userId) {
        error = null;
        message = null;
        try {
            tripService.removeMember(tripId, userId, currentUser.getUserId());
            message = "Member removed.";
            members = tripService.getTripMembers(tripId);
            trip = tripService.getTrip(tripId);
            balances = reportService.getMemberBalances(tripId);
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    /**
     * Users who could still be added: everyone not already on the trip.
     *
     * <p>Filtered here rather than in the query so the picker cannot offer a
     * choice that the service would then reject.
     */
    public List<UserDTO> getAddableUsers() {
        List<Long> currentIds = members.stream().map(TripMemberDTO::getUserId).toList();
        return userService.getAllUsers().stream()
                .filter(u -> !currentIds.contains(u.getUserId()))
                .toList();
    }

    // ------------------------------------------------------------------
    // Derived figures for the tabs
    // ------------------------------------------------------------------

    public List<TripStatus> getStatuses() {
        return List.of(TripStatus.values());
    }

    public BigDecimal getExpenseTotal() {
        return expenses.stream()
                .map(ExpenseDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getSettlementTotal() {
        return settlements.stream()
                .map(SettlementDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isHasItinerary() {
        return !itinerary.isEmpty();
    }

    public boolean isHasExpenses() {
        return !expenses.isEmpty();
    }

    public boolean isHasSettlements() {
        return !settlements.isEmpty();
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

    public List<TripMemberDTO> getMembers() {
        return members;
    }

    public List<ItineraryItemDTO> getItinerary() {
        return itinerary;
    }

    public List<ExpenseDTO> getExpenses() {
        return expenses;
    }

    public List<MemberBalanceDTO> getBalances() {
        return balances;
    }

    public List<SettlementDTO> getSettlements() {
        return settlements;
    }

    public boolean isMember() {
        return member;
    }

    public boolean isAdmin() {
        return admin;
    }

    public boolean isLoaded() {
        return trip != null;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public Long getNewMemberId() {
        return newMemberId;
    }

    public void setNewMemberId(Long newMemberId) {
        this.newMemberId = newMemberId;
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
