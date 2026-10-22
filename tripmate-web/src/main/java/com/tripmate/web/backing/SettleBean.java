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
    private List<TripMemberDTO> members = new ArrayList<>();
    private List<MemberBalanceDTO> balances = new ArrayList<>();
    private List<SettlementDTO> settlements = new ArrayList<>();

    private Long payerId;
    private Long receiverId;
    private BigDecimal amount;
    private String note;

    private boolean member;
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
        // Whoever is looking is the likely payer, so start there.
        payerId = currentUser.getUserId();
        refresh();
    }

    private void refresh() {
        members = new ArrayList<>(tripService.getTripMembers(tripId));
        balances = new ArrayList<>(reportService.getMemberBalances(tripId));
        settlements = new ArrayList<>(expenseService.getSettlementsForTrip(tripId));
    }

    public void save() {

        if (payerId == null || receiverId == null) {
            Toasts.error("Choose who paid, and who they paid.");
            return;
        }
        // Checked here as well as by the service and by a database constraint,
        // because it is the mistake most likely to be made in this form.
        if (payerId.equals(receiverId)) {
            Toasts.error("A repayment needs two different people.");
            return;
        }
        if (amount == null || amount.signum() <= 0) {
            Toasts.error("The amount must be greater than zero.");
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
            refresh();
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    public void delete(Long settlementId) {
        try {
            expenseService.deleteSettlement(settlementId, currentUser.getUserId());
            Toasts.success("Repayment removed.");
            refresh();
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    /** Fills the amount with what the chosen payer owes, as a starting point. */
    public void suggestAmount() {
        if (payerId == null) {
            return;
        }
        balances.stream()
                .filter(b -> b.getUserId().equals(payerId))
                .findFirst()
                .ifPresent(b -> amount = b.getNetBalance().abs());
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

    public void setPayerId(Long payerId) {
        this.payerId = payerId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
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
