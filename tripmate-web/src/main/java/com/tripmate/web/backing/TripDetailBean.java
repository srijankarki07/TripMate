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
import com.tripmate.dto.TripSummaryEmailDTO;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.TripStatus;
import com.tripmate.entity.TripSummaryKind;
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

    /**
     * When the summary was asked for, newest first.
     *
     * <p>Loaded for every member rather than only for administrators. The
     * summary is a message those members were themselves sent, so whether it
     * went out and when is not privileged, and a member hunting for it in an
     * inbox is exactly the person who needs to see that it was sent.
     */
    private List<TripSummaryEmailDTO> summaryEmails = new ArrayList<>();
    private List<ItineraryItemDTO> itinerary = new ArrayList<>();
    private List<ExpenseDTO> expenses = new ArrayList<>();
    /**
     * The gross figures, kept because {@link #positions} is built from them.
     *
     * <p>No longer read by this page's tables. They used to be, and the effect
     * was that recording a repayment changed nothing here: the repayment list
     * was the only place it showed up. The tables now read the figures that
     * account for repayments.
     */
    private List<MemberBalanceDTO> balances = new ArrayList<>();
    private List<SettlementDTO> settlements = new ArrayList<>();

    /**
     * The same figures the balances page works out: paid and share, plus the
     * repayments already recorded.
     *
     * <p>Built here rather than read from {@link #balances} because the two
     * answer different questions and the page used to answer only the first.
     * Recording a repayment changed nothing on this page, which left the
     * repayment list as the only place it showed up.
     */
    private List<BalancesBean.MemberPosition> positions = new ArrayList<>();

    /**
     * A short set of payments that would clear the trip, worked out the same
     * way the full balances page worked it out.
     *
     * <p>That page is gone and its two extra views are on the Balances tab, so
     * the arithmetic moved with them rather than being written a second time.
     */
    private List<BalancesBean.Transfer> suggested = new ArrayList<>();

    private String newMemberEmail;
    /** What the reader typed into the delete confirmation, checked against the name. */
    private String deleteConfirmName;
    private boolean member;
    private boolean admin;

    // ------------------------------------------------------------------
    // Dialog state
    //
    // Each of these is a real field rather than a widget variable, because a
    // failed action must leave its dialog open with the reader's typing still
    // in it. The bean decides whether the dialog stays open, so the decision
    // survives the response and is not made by a client-side callback that
    // cannot see whether the action succeeded.
    // ------------------------------------------------------------------

    /** The trip settings dialog, opened from the pencil beside the trip's name. */
    private boolean settingsOpen;

    /** The delete dialog, opened from the bin beside the trip's name. */
    private boolean deleteOpen;

    /** The "add someone" dialog on the Members tab. */
    private boolean addMemberDialogOpen;

    /** Why the open dialog's action was refused, or null. */
    private String dialogError;

    // ------------------------------------------------------------------
    // The summary send
    // ------------------------------------------------------------------

    /** Which half of the summary the next send is about. */
    private TripSummaryKind summaryKind = TripSummaryKind.BALANCES;

    /** True when the send goes to everybody, which is the default. */
    private boolean summaryToEveryone = true;

    /** Who to send to, when it is not everybody. */
    private List<Long> summaryRecipients = new ArrayList<>();

    /** Why the last send was refused, shown in the summary tab. */
    private String summaryError;

    /** The "email a summary" dialog on the Summary tab. */
    private boolean summaryDialogOpen;

    // ------------------------------------------------------------------
    // Editing the itinerary, from the Itinerary tab
    //
    // This lived on a page of its own, reached by a button that took the
    // reader away from the trip to change one line of it. The list is on this
    // page, so the editor is too.
    // ------------------------------------------------------------------

    /** Bound to the dialog's fields. */
    private ItineraryItemDTO itemForm = new ItineraryItemDTO();

    /** Null while adding; the item's id while editing it. */
    private Long editingItemId;

    private boolean itemDialogOpen;

    /** Why the open dialog's save was refused, or null. */
    private String itemDialogError;

    /**
     * The log entry whose details are being shown, or null.
     *
     * <p>The log line records how many people a send reached. That number is
     * the answer to "did it go out"; the names are the answer to "did it reach
     * the person asking", which is what somebody reading the log is usually
     * trying to find out.
     */
    private TripSummaryEmailDTO summaryDetail;

    private boolean summaryDetailOpen;
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
        summaryEmails = new ArrayList<>(
                tripService.getSummaryEmails(tripId, currentUser.getUserId()));
        itinerary = new ArrayList<>(itineraryService.getItineraryForTrip(tripId));
        expenses = new ArrayList<>(expenseService.getExpensesForTrip(tripId));
        refreshBalances();
    }

    /**
     * Reloads the money: the gross balances, the repayments, and the two
     * combined.
     *
     * <p>One method rather than three call sites each remembering to reload
     * part of it. The gross figures only change when an expense or the
     * membership changes, but the combined ones also change with every
     * repayment, and keeping them in step by hand is how they came apart.
     */
    private void refreshBalances() {
        balances = new ArrayList<>(reportService.getMemberBalances(tripId, currentUser.getUserId()));
        settlements = new ArrayList<>(expenseService.getSettlementsForTrip(tripId));
        positions = new ArrayList<>(BalancesBean.buildPositions(balances, settlements));
        suggested = new ArrayList<>(BalancesBean.suggestTransfers(positions));
    }

    // ------------------------------------------------------------------
    // Editing the trip
    // ------------------------------------------------------------------

    /**
     * Opens the trip settings dialog, from the pencil beside the trip's name.
     *
     * <p>The two dialogs in the hero are opened from the buttons that are
     * re-rendered with them, so "update this form" would redraw both. Each
     * opener therefore closes the other. That is also what keeps the server's
     * idea of what is open in step when a reader dismisses one with the
     * dialog's own close icon, which is handled in the browser and tells the
     * server nothing.
     */
    public void openSettings() {
        dialogError = null;
        deleteOpen = false;
        settingsOpen = true;
    }

    public void closeSettings() {
        dialogError = null;
        settingsOpen = false;
    }

    /** Opens the delete dialog, from the bin beside the trip's name. */
    public void openDelete() {
        dialogError = null;
        deleteConfirmName = null;
        settingsOpen = false;
        deleteOpen = true;
    }

    public void closeDelete() {
        dialogError = null;
        deleteConfirmName = null;
        deleteOpen = false;
    }

    /**
     * Writes the edited trip back.
     *
     * <p>The dialog stays open when the save is refused, with every field
     * exactly as the reader left it and the reason shown beside them. Closing
     * it would throw away the edit and leave the explanation in a toast the
     * reader is not looking at, because the dialog was covering the page.
     */
    public void save() {
        dialogError = null;
        try {
            trip = tripService.updateTrip(trip, currentUser.getUserId());
            Toasts.success("Trip saved.");
            settingsOpen = false;
            // The header shows the name and dates, which are read from the
            // refreshed DTO, so there is nothing else to reload for those.
            members = new ArrayList<>(tripService.getTripMembers(tripId));
        } catch (RuntimeException ex) {
            dialogError = Messages.describe(ex);
        }
    }

    /** @return a redirect to the trip list once the trip is gone */
    public String delete() {
        dialogError = null;

        // Checked again here as well as by the button's state. The client-side
        // hint is a convenience for the reader, not a guard: it is rendered in
        // the browser, and a request can be sent without it.
        if (!isDeleteConfirmed()) {
            dialogError = "That does not match this trip's name. Check the spelling and try again.";
            return null;
        }

        try {
            tripService.deleteTrip(tripId, currentUser.getUserId());
            Toasts.success("Trip deleted.");
            return "/trips/list.xhtml?faces-redirect=true";
        } catch (RuntimeException ex) {
            dialogError = Messages.describe(ex);
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

    /** Opens the item dialog with an empty form. */
    public void openAddItem() {
        itemForm = new ItineraryItemDTO();
        editingItemId = null;
        itemDialogError = null;
        itemDialogOpen = true;
    }

    /** Opens the item dialog on one of the plan's items. */
    public void openEditItem(Long itemId) {
        ItineraryItemDTO item = itineraryService.getItem(itemId);
        if (item == null) {
            Toasts.error("That item no longer exists.");
            load();
            return;
        }
        itemForm = item;
        editingItemId = itemId;
        itemDialogError = null;
        itemDialogOpen = true;
    }

    public void closeItemDialog() {
        itemForm = new ItineraryItemDTO();
        editingItemId = null;
        itemDialogError = null;
        itemDialogOpen = false;
    }

    /**
     * Adds the item in the dialog, or saves the one being edited.
     *
     * <p>The field-level rules are the components' own; this covers the one
     * rule that spans two fields, which no single component can see.
     */
    public void saveItem() {
        itemDialogError = null;
        if (itemForm.getStartTime() != null && itemForm.getEndTime() != null
                && itemForm.getEndTime().isBefore(itemForm.getStartTime())) {
            itemDialogError = "The end time cannot be before the start time.";
            return;
        }
        try {
            if (editingItemId == null) {
                itineraryService.addItem(tripId, itemForm, currentUser.getUserId());
                Toasts.success("Added to the itinerary.");
            } else {
                itemForm.setItemId(editingItemId);
                itineraryService.updateItem(itemForm, currentUser.getUserId());
                Toasts.success("Item saved.");
            }
            closeItemDialog();
            // The table, the count above it and the summary tiles all read from
            // this list, so one reload keeps the whole page telling one story.
            itinerary = new ArrayList<>(itineraryService.getItineraryForTrip(tripId));
        } catch (RuntimeException ex) {
            itemDialogError = Messages.describe(ex);
        }
    }

    /**
     * Removes one of the plan's items, by its own id.
     *
     * <p>Called from the row's menu rather than from the editor. Deleting is a
     * thing done to an item in a list, and putting it inside the dialog meant
     * opening the item first, which is the opposite order.
     */
    public void deleteRowItem(Long itemId) {
        if (itemId == null) {
            return;
        }
        try {
            itineraryService.deleteItem(itemId, currentUser.getUserId());
            Toasts.success("Item removed.");
            if (itemId.equals(editingItemId)) {
                closeItemDialog();
            }
            itinerary = new ArrayList<>(itineraryService.getItineraryForTrip(tripId));
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /** Quick times for the picker, so the common cases need no typing. */
    public List<java.time.LocalTime> getQuarterHours() {
        List<java.time.LocalTime> times = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(java.time.LocalTime.of(hour, 0));
            times.add(java.time.LocalTime.of(hour, 15));
            times.add(java.time.LocalTime.of(hour, 30));
            times.add(java.time.LocalTime.of(hour, 45));
        }
        return times;
    }

    public ItineraryItemDTO getItemForm() {
        return itemForm;
    }

    public Long getEditingItemId() {
        return editingItemId;
    }

    public boolean isEditingItem() {
        return editingItemId != null;
    }

    public boolean isItemDialogOpen() {
        return itemDialogOpen;
    }

    /** @return the item dialog's title, which depends on what it is for */
    public String getItemDialogHeader() {
        return editingItemId == null ? "Add an item" : "Edit this item";
    }

    /** @return why the last item save was refused, or null */
    public String getItemDialogError() {
        return itemDialogError;
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
    /** Opens the dialog that adds somebody, from the Members tab toolbar. */
    public void openAddMember() {
        dialogError = null;
        newMemberEmail = null;
        addMemberDialogOpen = true;
    }

    public void closeAddMember() {
        dialogError = null;
        newMemberEmail = null;
        addMemberDialogOpen = false;
    }

    /**
     * Adds somebody by their email address.
     *
     * <p>As with the settings dialog, a refusal leaves the dialog open with the
     * address still typed in it, so the reader corrects one thing rather than
     * starting again.
     */
    public void inviteMember() {
        dialogError = null;
        if (newMemberEmail == null || newMemberEmail.isBlank()) {
            dialogError = "Enter the email address of the person you want to add.";
            return;
        }
        try {
            TripMemberDTO added = tripService.addMemberByEmail(
                    tripId, newMemberEmail.trim(), TripRole.MEMBER, currentUser.getUserId());
            Toasts.success(added.getFullName() + " joined the trip.");
            newMemberEmail = null;
            addMemberDialogOpen = false;
            trip = tripService.getTrip(tripId);
            members = new ArrayList<>(tripService.getTripMembers(tripId));
            refreshBalances();
        } catch (RuntimeException ex) {
            dialogError = Messages.describe(ex);
        }
    }

    public void removeMember(Long userId) {
        try {
            tripService.removeMember(tripId, userId, currentUser.getUserId());
            Toasts.success("Member removed.");
            trip = tripService.getTrip(tripId);
            members = new ArrayList<>(tripService.getTripMembers(tripId));
            refreshBalances();
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
     * Emails the chosen half of the summary to the chosen people.
     *
     * <p>Any member may do this, not only an administrator. Everything it
     * contains is already visible to every member, and who receives it is the
     * sender's choice, so there is nothing here for a role to protect.
     *
     * <p>A refusal is shown in the tab beside the form and every choice the
     * reader has made is kept, so the fix is to change one thing rather than to
     * set the whole thing up again.
     */
    /**
     * Opens the summary dialog, from the Summary tab toolbar.
     *
     * <p>Every choice is reset first. The dialog is a question that is asked and
     * answered, not a form that remembers: reopening it and finding the last
     * send's recipients still ticked is how the same three people get mailed
     * twice, and the reader has no way of telling that the ticks were not
     * theirs.
     */
    public void openSummary() {
        summaryError = null;
        summaryToEveryone = true;
        summaryRecipients = new ArrayList<>();
        summaryKind = TripSummaryKind.BALANCES;
        // Then start on a half that has something in it, so the send button is
        // not disabled the moment the dialog opens on a trip with only one of
        // the two.
        if (isSummaryKindUnavailable()) {
            summaryKind = isCanSendItinerary() ? TripSummaryKind.ITINERARY : TripSummaryKind.BALANCES;
        }
        summaryDialogOpen = true;
    }

    public void closeSummary() {
        summaryError = null;
        summaryDialogOpen = false;
    }

    /** Opens the panel describing one entry of the log. */
    public void openSummaryDetail(TripSummaryEmailDTO entry) {
        summaryDetail = entry;
        summaryDetailOpen = entry != null;
    }

    public void closeSummaryDetail() {
        summaryDetail = null;
        summaryDetailOpen = false;
    }

    public void emailSummary() {
        summaryError = null;
        try {
            TripSummaryEmailDTO entry = tripService.emailTripSummary(
                    tripId, currentUser.getUserId(), summaryKind,
                    summaryToEveryone ? List.of() : new ArrayList<>(summaryRecipients));
            // Reloaded so the new line is in the log below straight away. The
            // send is the action and the log is its record; leaving the record
            // stale until the next page load would hide the thing the reader
            // just did, which is the same fault this log exists to fix.
            summaryEmails = new ArrayList<>(
                    tripService.getSummaryEmails(tripId, currentUser.getUserId()));
            // Closed only on success. A refusal leaves the dialog open with the
            // content and the recipients exactly as they were chosen, so the
            // fix is to change one thing rather than set it up again.
            summaryDialogOpen = false;
            Toasts.success(describeSend(entry));
        } catch (RuntimeException ex) {
            summaryError = Messages.describe(ex);
        }
    }

    /**
     * What to say about a send that has just been recorded.
     *
     * <p>Says what actually happened rather than assuming it worked. A send
     * refused by the mail server used to raise "Summary sent to four people",
     * which is the application telling the reader something it did not know.
     */
    private static String describeSend(TripSummaryEmailDTO entry) {
        String what = entry.getKind() == TripSummaryKind.ITINERARY
                ? "The itinerary" : "The balance sheet";
        int sent = entry.getRecipientCount() == null ? 0 : entry.getRecipientCount();
        int failed = entry.getFailedCount() == null ? 0 : entry.getFailedCount();

        return switch (entry.getStatus()) {
            case SENT -> what + " went to " + sent + (sent == 1 ? " person." : " people.");
            case PARTIAL -> what + " went to " + sent + " of " + (sent + failed)
                    + " people; " + failed + " could not be reached.";
            case FAILED -> what + " could not be sent to anybody. Check the mail settings.";
            case NOT_CONFIGURED -> what + " was written to the server log: no mail server "
                    + "is configured for this deployment.";
        };
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

    /**
     * Whether the outstanding figures differ from the gross ones.
     *
     * <p>Only then is it worth saying so. On a trip nobody has repaid anything
     * the two are the same number, and a note explaining a difference that is
     * not there is noise.
     */
    public boolean isRepaymentsAffectFigures() {
        return positions.stream().anyMatch(p -> !p.isUntouchedBySettlements());
    }

    /** @return true when the row belongs to the signed-in user */
    public boolean isMyRow(BalancesBean.MemberPosition position) {
        return position != null && position.getUserId() != null
                && position.getUserId().equals(currentUser.getUserId());
    }

    // ------------------------------------------------------------------
    // The summary send
    // ------------------------------------------------------------------

    /** @return the two halves that can be sent, in the order the form offers them */
    public List<TripSummaryKind> getSummaryKinds() {
        return List.of(TripSummaryKind.ITINERARY, TripSummaryKind.BALANCES);
    }

    /** @return true when there is a plan to send */
    public boolean isCanSendItinerary() {
        return !itinerary.isEmpty();
    }

    /** @return true when there is money to report */
    public boolean isCanSendBalances() {
        return !expenses.isEmpty();
    }

    /** @return true when either half has something in it */
    public boolean isCanSendAnything() {
        return isCanSendItinerary() || isCanSendBalances();
    }

    /** @return true when the chosen half is the one with nothing in it */
    public boolean isSummaryKindUnavailable() {
        return summaryKind == TripSummaryKind.ITINERARY ? !isCanSendItinerary() : !isCanSendBalances();
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
    /** @return the summary log, newest first, never null */
    public List<TripSummaryEmailDTO> getSummaryEmails() {
        return summaryEmails;
    }

    /** @return true when the summary has never been sent for this trip */
    public boolean isSummaryNeverSent() {
        return summaryEmails.isEmpty();
    }

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
    public List<SettlementDTO> getSettlements() {
        return settlements;
    }

    /**
     * The list itself, deliberately, and mutable.
     *
     * <p>PrimeFaces sorts a table by sorting the collection this returns, in
     * place, and then reads it again to render. See {@link #getMembers()} for
     * the long version; the same contract applies here.
     *
     * @return one row per member, paid and share plus the repayments recorded
     */
    public List<BalancesBean.MemberPosition> getPositions() {
        return positions;
    }

    /** @return the suggested payments, empty when everybody is square */
    public List<BalancesBean.Transfer> getSuggested() {
        return suggested;
    }

    public boolean isHasSuggestions() {
        return !suggested.isEmpty();
    }

    /** The largest distance from square that anybody on the trip is. */
    public BigDecimal getLargestPosition() {
        return positions.stream()
                .map(BalancesBean.MemberPosition::getOutstandingMagnitude)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    /** @return true when there is a position worth drawing a bar for */
    public boolean isHasPositionBar() {
        return !positions.isEmpty() && getLargestPosition().signum() > 0;
    }

    /**
     * How far along its half of the bar one member's position reaches.
     *
     * <p>Scaled against the largest position on the trip, so the rows are
     * comparable with each other rather than each drawn full length. The floor
     * of four keeps a real but tiny position visible as a mark.
     *
     * @param position the member's row, which the view passes in
     * @return 0 to 100
     */
    public int barWidth(BalancesBean.MemberPosition position) {
        BigDecimal largest = getLargestPosition();
        if (position == null || largest.signum() == 0) {
            return 0;
        }
        BigDecimal magnitude = position.getOutstandingMagnitude();
        if (magnitude.signum() == 0) {
            return 0;
        }
        int percent = magnitude.multiply(BigDecimal.valueOf(100))
                .divide(largest, 0, java.math.RoundingMode.HALF_UP)
                .intValue();
        return Math.max(4, Math.min(100, percent));
    }

    public boolean isMember() {
        return member;
    }

    public boolean isSettingsOpen() {
        return settingsOpen;
    }

    public boolean isDeleteOpen() {
        return deleteOpen;
    }

    public boolean isAddMemberDialogOpen() {
        return addMemberDialogOpen;
    }

    /** @return why the open dialog's action was refused, or null */
    public String getDialogError() {
        return dialogError;
    }

    public TripSummaryKind getSummaryKind() {
        return summaryKind;
    }

    public void setSummaryKind(TripSummaryKind summaryKind) {
        this.summaryKind = summaryKind;
    }

    public boolean isSummaryToEveryone() {
        return summaryToEveryone;
    }

    public void setSummaryToEveryone(boolean summaryToEveryone) {
        this.summaryToEveryone = summaryToEveryone;
    }

    /**
     * The list itself, and mutable, for the same reason the tables are: the
     * multi-select writes into it and the page reads it back in the same
     * response.
     */
    public List<Long> getSummaryRecipients() {
        return summaryRecipients;
    }

    public void setSummaryRecipients(List<Long> summaryRecipients) {
        this.summaryRecipients = summaryRecipients == null
                ? new ArrayList<>() : new ArrayList<>(summaryRecipients);
    }

    /** @return why the last summary send was refused, or null */
    public String getSummaryError() {
        return summaryError;
    }

    public boolean isSummaryDialogOpen() {
        return summaryDialogOpen;
    }

    public TripSummaryEmailDTO getSummaryDetail() {
        return summaryDetail;
    }

    public boolean isSummaryDetailOpen() {
        return summaryDetailOpen;
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

    /**
     * How many administrators the trip has.
     *
     * <p>Exists so the view can ask the same question the service asks. The
     * service refuses to remove or demote an administrator when
     * {@code countAdmins} would fall to zero, and the view has to decide
     * whether to offer those buttons at all. It previously decided using the
     * total member count as a proxy, which is a different question: a trip with
     * one administrator and one member has two members and one administrator,
     * so the buttons appeared and every press was refused.
     *
     * <p>Derived from the list already loaded rather than counted in the
     * database, so it cannot disagree with the table being rendered from the
     * same list.
     *
     * @return the number of members whose role is administrator, never negative
     */
    public int getAdminCount() {
        int count = 0;
        for (TripMemberDTO m : members) {
            if (m.getRole() == TripRole.ADMIN) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the given member is the trip's only administrator.
     *
     * <p>Reads better at the call site than the counting comparison, and keeps
     * the rule in one place: "the last administrator cannot be removed, demoted
     * or allowed to leave" is one rule, and it is asked about a specific person
     * often enough to deserve a name.
     *
     * @param memberId the user to ask about
     * @return true when they administer the trip and nobody else does
     */
    public boolean isLastAdmin(Long memberId) {
        if (getAdminCount() != 1) {
            return false;
        }
        for (TripMemberDTO m : members) {
            if (m.getUserId().equals(memberId)) {
                return m.getRole() == TripRole.ADMIN;
            }
        }
        return false;
    }

    /** @return true when the signed-in user is the trip's only administrator */
    public boolean isSoleAdmin() {
        return admin && getAdminCount() == 1;
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
