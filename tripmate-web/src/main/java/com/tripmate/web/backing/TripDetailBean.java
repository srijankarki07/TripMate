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

    private String newMemberEmail;
    /** What the reader typed into the delete confirmation, checked against the name. */
    private String deleteConfirmName;
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
        balances = new ArrayList<>(reportService.getMemberBalances(tripId, currentUser.getUserId()));
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
        // Checked again here as well as by the button's own disabled state. The
        // disabled attribute is a convenience for the reader, not a guard: it is
        // rendered on the client, and a request can be sent without it.
        if (!isDeleteConfirmed()) {
            Toasts.error("Type the trip's name to confirm deleting it.");
            return null;
        }

        try {
            tripService.deleteTrip(tripId, currentUser.getUserId());
            Toasts.success("Trip deleted.");
            return "/trips/list.xhtml?faces-redirect=true";
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
            return null;
        }
    }

    /**
     * Whether the typed confirmation matches the trip's name.
     *
     * <p>A dialog with a Yes button is enough for reversible mistakes and is not
     * enough for this one. Deleting a trip destroys the itinerary, every expense
     * and every recorded repayment, and there is no undo. Making the reader
     * type the name means they have to look at which trip they are about to
     * destroy and reproduce it, which is the difference between confirming an
     * action and dismissing a box.
     *
     * <p>Compared case-insensitively and trimmed, because the point is that the
     * reader read the name rather than that they matched its capitalisation.
     */
    public boolean isDeleteConfirmed() {
        if (trip == null || trip.getTripName() == null || deleteConfirmName == null) {
            return false;
        }
        return trip.getTripName().trim().equalsIgnoreCase(deleteConfirmName.trim());
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Adds someone by their exact email address.
     *
     * <p>Replaces a picker that listed every account on the platform. The
     * address has to match exactly: a partial search would be the same
     * directory again, one letter at a time.
     */
    public void inviteMember() {
        if (newMemberEmail == null || newMemberEmail.isBlank()) {
            Toasts.error("Enter the email address of the person you want to add.");
            return;
        }
        try {
            TripMemberDTO added = tripService.addMemberByEmail(
                    tripId, newMemberEmail.trim(), TripRole.MEMBER, currentUser.getUserId());
            Toasts.success(added.getFullName() + " joined the trip.");
            newMemberEmail = null;
            members = new ArrayList<>(tripService.getTripMembers(tripId));
            trip = tripService.getTrip(tripId);
            balances = new ArrayList<>(reportService.getMemberBalances(tripId, currentUser.getUserId()));
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
            balances = new ArrayList<>(reportService.getMemberBalances(tripId, currentUser.getUserId()));
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /**
     * Makes a member an administrator.
     *
     * <p>Needed because {@code removeMember} refuses to remove a trip's last
     * administrator and tells the caller to promote someone else first. Until
     * this existed there was no way to do that from the application at all.
     */
    public void promoteMember(Long userId) {
        changeRole(userId, TripRole.ADMIN, "is now an administrator.");
    }

    /** Takes administrator rights away, refusing to leave the trip unmanaged. */
    public void demoteMember(Long userId) {
        changeRole(userId, TripRole.MEMBER, "is now an ordinary member.");
    }

    private void changeRole(Long userId, TripRole role, String outcome) {
        try {
            TripMemberDTO updated =
                    tripService.updateMemberRole(tripId, userId, role, currentUser.getUserId());
            Toasts.success(updated.getFullName() + " " + outcome);
            members = new ArrayList<>(tripService.getTripMembers(tripId));
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /**
     * Emails every member the trip's closing summary.
     *
     * <p>Administrators only, enforced in the service as well as by the
     * button's visibility: it sends mail to everybody on the trip, which is not
     * something a page should be the only guard on.
     */
    public void emailSummary() {
        try {
            int sent = tripService.emailTripSummary(tripId, currentUser.getUserId());
            Toasts.success("Summary sent to " + sent
                    + (sent == 1 ? " person." : " people."));
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /** Removes the signed-in user from the trip of their own accord. */
    public String leaveTrip() {
        try {
            tripService.leaveTrip(tripId, currentUser.getUserId());
            Toasts.success("You left the trip.");
            return "/trips/list.xhtml?faces-redirect=true";
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
            return null;
        }
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

    public String getNewMemberEmail() {
        return newMemberEmail;
    }

    public String getDeleteConfirmName() {
        return deleteConfirmName;
    }

    public void setDeleteConfirmName(String deleteConfirmName) {
        this.deleteConfirmName = deleteConfirmName;
    }

    public void setNewMemberEmail(String newMemberEmail) {
        this.newMemberEmail = newMemberEmail;
    }

    public boolean isAdmin() {
        return admin;
    }

    public boolean isLoaded() {
        return trip != null;
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
