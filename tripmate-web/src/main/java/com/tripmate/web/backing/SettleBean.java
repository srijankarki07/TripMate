package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Recording a repayment between two members.
 *
 * <p>A settlement is a record of money that actually changed hands, not a
 * figure the system decides. The balances page suggests payments; this page
 * records the ones that happened.
 *
 * <h2>Arriving from a row of the balances table</h2>
 *
 * <p>The page can be opened with {@code payerId}, {@code receiverId} and
 * {@code amount} in the query string, which is how a reader gets here by
 * pressing the outstanding amount on their own row. When any of them is given
 * the form is filled from it, and whatever is missing is worked out from the
 * same suggestions the balances page shows, so the three fields agree with each
 * other rather than being filled independently.
 */
@Named
@ViewScoped
public class SettleBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private ReportServiceRemote reportService;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private Long tripId;
    private TripDTO trip;
    /**
     * The trip's members.
     *
     * <p>Read through {@link #getPayerChoices()} and
     * {@link #getReceiverChoices()} rather than directly, because the two
     * dropdowns must not offer the same person twice.
     */
    private List<TripMemberDTO> members = new ArrayList<>();
    private List<BalancesBean.MemberPosition> positions = new ArrayList<>();
    private List<SettlementDTO> settlements = new ArrayList<>();
    private List<BalancesBean.Transfer> suggested = new ArrayList<>();

    private Long payerId;
    private Long receiverId;
    private BigDecimal amount;
    private String note;

    /**
     * What the query string asked for, kept apart from the form's own state.
     *
     * <p>Binding the view parameters straight onto {@code payerId} would make
     * "the URL named nobody" and "the reader has not chosen yet" the same
     * value, and the form has to tell those apart: one means start from the
     * suggestion, the other means the reader cleared the field.
     */
    private Long requestedPayerId;
    private Long requestedReceiverId;
    private BigDecimal requestedAmount;

    private boolean member;

    /**
     * A refusal to explain in the form itself rather than only in a toast.
     *
     * <p>A failed save leaves every field exactly as it was, which is the
     * point: the reader's typing is not thrown away and nothing about the page
     * moves under them. The message has to be somewhere they are still looking,
     * and that is the form, not the corner of the screen.
     */
    private String formError;

    /** Whether the record-a-repayment dialog is open. */
    private boolean formOpen;

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

        // Whoever is looking is the likely payer, unless the row they pressed
        // named somebody else.
        payerId = isOnTrip(requestedPayerId) ? requestedPayerId : currentUser.getUserId();

        boolean arrivedFromRow = requestedPayerId != null || requestedReceiverId != null;
        if (isOnTrip(requestedReceiverId) && !requestedReceiverId.equals(payerId)) {
            receiverId = requestedReceiverId;
        } else if (arrivedFromRow) {
            // The row said who is paying but not to whom. The balances page
            // already works out who they should be paying; use the same answer
            // rather than making the reader find it again.
            receiverId = firstCreditorFor(payerId);
        } else {
            receiverId = null;
        }

        if (requestedAmount != null && requestedAmount.signum() > 0) {
            amount = requestedAmount;
        } else if (arrivedFromRow) {
            amount = owedBetween(payerId, receiverId);
        } else {
            amount = null;
        }
    }

    private void refresh() {
        members = new ArrayList<>(tripService.getTripMembers(tripId));
        List<MemberBalanceDTO> balances =
                new ArrayList<>(reportService.getMemberBalances(tripId, currentUser.getUserId()));
        settlements = new ArrayList<>(expenseService.getSettlementsForTrip(tripId));
        positions = new ArrayList<>(BalancesBean.buildPositions(balances, settlements));
        suggested = new ArrayList<>(BalancesBean.suggestTransfers(positions));
    }

    /** Opens the dialog, from the toolbar button or the empty state. */
    public void openForm() {
        formError = null;
        // Start from the suggestion again: whoever is looking is the likely
        // payer, and the amount is what that leaves them owing.
        payerId = currentUser.getUserId();
        receiverId = firstCreditorFor(payerId);
        amount = owedBetween(payerId, receiverId);
        formOpen = true;
    }

    /** Closes the dialog without recording anything. */
    public void closeForm() {
        formError = null;
        formOpen = false;
    }

    public void save() {
        formError = null;

        if (payerId == null || receiverId == null) {
            fail("Choose who paid, and who they paid.");
            return;
        }
        // Checked here as well as by the service and by a database constraint,
        // because it is the mistake most likely to be made in this form. The
        // dropdowns already hide the counterparty, so reaching this means the
        // request was built by hand.
        if (payerId.equals(receiverId)) {
            fail("A repayment needs two different people.");
            return;
        }
        if (amount == null || amount.signum() <= 0) {
            fail("The amount must be greater than zero.");
            return;
        }

        SettlementDTO dto = new SettlementDTO();
        dto.setTripId(tripId);
        dto.setPayerId(payerId);
        dto.setReceiverId(receiverId);
        dto.setAmount(amount);
        dto.setNote(note == null || note.isBlank() ? null : note.trim());
        dto.setSettledAt(LocalDateTime.now(ZoneId.systemDefault()));

        try {
            SettlementDTO recorded = expenseService.recordSettlement(dto, currentUser.getUserId());
            Toasts.success(recorded.getPayerName() + " paid " + recorded.getReceiverName()
                    + " NPR " + money(recorded.getAmount()) + ".");
            amount = null;
            note = null;
            // Closed only on success. A refusal leaves the dialog open with
            // every field as the reader left it.
            formOpen = false;
            refresh();
        } catch (RuntimeException ex) {
            // The form is left exactly as it was, so nothing the reader typed is
            // lost and the page does not move. Only the reason is added.
            fail(Messages.describe(ex));
        }
    }

    public void delete(Long settlementId) {
        formError = null;
        try {
            expenseService.deleteSettlement(settlementId, currentUser.getUserId());
            Toasts.success("Repayment removed.");
            refresh();
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /**
     * Fills the amount with what the two chosen people owe each other.
     *
     * <p>Reads the <em>outstanding</em> figures, which already account for the
     * repayments recorded on this trip. It previously read the gross net
     * balance, so it ignored anything already repaid and offered the same
     * figure whatever the receiver was, which is the opposite of what the
     * button says it does.
     *
     * <p>The amount is the smaller of what the payer still owes and what the
     * receiver is still owed, so it never suggests moving more than either side
     * has left. With no receiver chosen it offers the payer's whole debt.
     */
    public void suggestAmount() {
        formError = null;
        if (payerId == null) {
            return;
        }

        BigDecimal owes = debtOf(payerId);
        if (owes.signum() <= 0) {
            fail(nameOf(payerId) + " is not owed anything, so there is nothing for them to pay.");
            amount = null;
            return;
        }

        if (receiverId == null) {
            amount = owes;
            return;
        }

        BigDecimal due = creditOf(receiverId);
        if (due.signum() <= 0) {
            fail(nameOf(receiverId) + " is not owed anything, so there is nothing to settle with them.");
            amount = null;
            return;
        }
        amount = owes.min(due);
    }

    // ------------------------------------------------------------------
    // The figures behind the form
    // ------------------------------------------------------------------

    private BalancesBean.MemberPosition positionOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return positions.stream()
                .filter(p -> userId.equals(p.getUserId()))
                .findFirst()
                .orElse(null);
    }

    /** What the person still has to pay, as a positive number, or zero. */
    private BigDecimal debtOf(Long userId) {
        BalancesBean.MemberPosition position = positionOf(userId);
        return position == null ? BigDecimal.ZERO : position.getOutstanding().negate().max(BigDecimal.ZERO);
    }

    /** What the person is still owed, as a positive number, or zero. */
    private BigDecimal creditOf(Long userId) {
        BalancesBean.MemberPosition position = positionOf(userId);
        return position == null ? BigDecimal.ZERO : position.getOutstanding().max(BigDecimal.ZERO);
    }

    /** The amount that would clear the payer's debt to this receiver. */
    private BigDecimal owedBetween(Long payer, Long receiver) {
        if (payer == null) {
            return null;
        }
        BigDecimal owes = debtOf(payer);
        if (receiver == null) {
            return owes.signum() > 0 ? owes : null;
        }
        BigDecimal due = creditOf(receiver);
        BigDecimal both = owes.min(due);
        return both.signum() > 0 ? both : null;
    }

    /** The first person the balances page suggests this debtor should pay. */
    private Long firstCreditorFor(Long payer) {
        if (payer == null) {
            return null;
        }
        return suggested.stream()
                .filter(t -> payer.equals(t.getFromId()))
                .map(BalancesBean.Transfer::getToId)
                .findFirst()
                .orElse(null);
    }

    private boolean isOnTrip(Long userId) {
        return userId != null && members.stream()
                .anyMatch(m -> userId.equals(m.getUserId()));
    }

    private String nameOf(Long userId) {
        return members.stream()
                .filter(m -> userId != null && userId.equals(m.getUserId()))
                .map(TripMemberDTO::getFullName)
                .findFirst()
                .orElse("That person");
    }

    private void fail(String reason) {
        formError = reason;
    }

    private static String money(BigDecimal value) {
        return value == null ? "0.00"
                : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
     * Everyone who could have paid: the whole trip, less whoever the reader
     * has already named as the receiver.
     *
     * <p>A repayment from somebody to themselves is not a repayment, so the
     * counterparty is not offered at all rather than offered and then refused.
     */
    public List<TripMemberDTO> getPayerChoices() {
        return members.stream()
                .filter(m -> !m.getUserId().equals(receiverId))
                .toList();
    }

    /** Everyone who could have received it: the whole trip, less the payer. */
    public List<TripMemberDTO> getReceiverChoices() {
        return members.stream()
                .filter(m -> !m.getUserId().equals(payerId))
                .toList();
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

    public boolean isHasSettlements() {
        return !settlements.isEmpty();
    }

    public boolean isMember() {
        return member;
    }

    public boolean isLoaded() {
        return trip != null;
    }

    public Long getPayerId() {
        return payerId;
    }

    /**
     * Records who is paying, and drops the receiver if it was the same person.
     *
     * <p>The dropdowns hide each other's selections, but a value can still
     * arrive from a stale request or from the query string. Clearing it here
     * means the form can never hold a repayment from somebody to themselves,
     * rather than holding one and refusing it on submit.
     */
    public void setPayerId(Long payerId) {
        this.payerId = payerId;
        if (payerId != null && payerId.equals(receiverId)) {
            receiverId = null;
        }
    }

    public Long getReceiverId() {
        return receiverId;
    }

    /** Records who received it, dropping the payer if it was the same person. */
    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
        if (receiverId != null && receiverId.equals(payerId)) {
            payerId = null;
        }
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getFormError() {
        return formError;
    }

    public boolean isFormOpen() {
        return formOpen;
    }

    public Long getRequestedPayerId() {
        return requestedPayerId;
    }

    public void setRequestedPayerId(Long requestedPayerId) {
        this.requestedPayerId = requestedPayerId;
    }

    public Long getRequestedReceiverId() {
        return requestedReceiverId;
    }

    public void setRequestedReceiverId(Long requestedReceiverId) {
        this.requestedReceiverId = requestedReceiverId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public void setRequestedAmount(BigDecimal requestedAmount) {
        this.requestedAmount = requestedAmount;
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
