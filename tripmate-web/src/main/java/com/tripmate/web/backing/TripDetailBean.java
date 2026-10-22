package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
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
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

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
    private List<TripMemberDTO> members = new ArrayList<>();
    private List<ItineraryItemDTO> itinerary = new ArrayList<>();
    private List<ExpenseDTO> expenses = new ArrayList<>();
    private List<MemberBalanceDTO> balances = new ArrayList<>();
    private List<SettlementDTO> settlements = new ArrayList<>();

    private Long newMemberId;
    private boolean member;
    private boolean admin;
    /** Loads everything the page shows. Called after the view parameter is set. */
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

        Long userId = currentUser.getUserId();
        TripRole role = tripService.getMemberRole(tripId, userId);
        member = role != null;
        admin = role == TripRole.ADMIN;

        // The filter guarantees a signed-in user but not that they belong to
        // this trip. A non-member sees nothing rather than a partly loaded page.
        if (!member) {
            loadError = "You are not a member of this trip.";
            trip = null;
            return;
        }

        members = new ArrayList<>(tripService.getTripMembers(tripId));
        itinerary = new ArrayList<>(itineraryService.getItineraryForTrip(tripId));
        expenses = new ArrayList<>(expenseService.getExpensesForTrip(tripId));
        balances = new ArrayList<>(reportService.getMemberBalances(tripId));
        settlements = new ArrayList<>(expenseService.getSettlementsForTrip(tripId));
    }

    // ------------------------------------------------------------------
    // Editing the trip
    // ------------------------------------------------------------------

    public void save() {
        try {
            trip = tripService.updateTrip(trip, currentUser.getUserId());
            Toasts.success("Trip saved.");
            // The header shows the name and dates, which are read from the
            // refreshed DTO, so there is nothing else to reload for those.
            members = new ArrayList<>(tripService.getTripMembers(tripId));
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /** @return a redirect to the trip list once the trip is gone */
    public String delete() {
        try {
            tripService.deleteTrip(tripId, currentUser.getUserId());
            return "/trips/list.xhtml?faces-redirect=true";
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    public void addMember() {
        if (newMemberId == null) {
            Toasts.error("Choose someone to add.");
            return;
        }
        try {
            UserDTO user = userService.getUser(newMemberId);
            tripService.addMember(tripId, newMemberId, TripRole.MEMBER, currentUser.getUserId());
            Toasts.success((user == null ? "Member" : user.getFullName()) + " joined the trip.");
            newMemberId = null;
            members = new ArrayList<>(tripService.getTripMembers(tripId));
            trip = tripService.getTrip(tripId);
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    public void removeMember(Long userId) {
        try {
            tripService.removeMember(tripId, userId, currentUser.getUserId());
            Toasts.success("Member removed.");
            members = new ArrayList<>(tripService.getTripMembers(tripId));
            trip = tripService.getTrip(tripId);
            balances = new ArrayList<>(reportService.getMemberBalances(tripId));
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
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
    public List<TripMemberDTO> getMembers() {
        return members;
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
    public List<ItineraryItemDTO> getItinerary() {
        return itinerary;
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
    public List<ExpenseDTO> getExpenses() {
        return expenses;
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
    public List<MemberBalanceDTO> getBalances() {
        return balances;
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

    public Long getNewMemberId() {
        return newMemberId;
    }

    public void setNewMemberId(Long newMemberId) {
        this.newMemberId = newMemberId;
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
