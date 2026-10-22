package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;

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
    private List<TripMemberDTO> members = List.of();
    private List<MemberBalanceDTO> balances = List.of();
    private List<SettlementDTO> settlements = List.of();

    private Long payerId;
    private Long receiverId;
    private BigDecimal amount;
    private String note;

    private boolean member;
    private String error;
    private String message;

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
        member = tripService.isMember(tripId, currentUser.getUserId());
        if (!member) {
            error = "You are not a member of this trip.";
            trip = null;
            return;
        }
        // Whoever is looking is the likely payer, so start there.
        payerId = currentUser.getUserId();
        refresh();
    }

    private void refresh() {
        members = tripService.getTripMembers(tripId);
        balances = reportService.getMemberBalances(tripId);
        settlements = expenseService.getSettlementsForTrip(tripId);
    }

    public void save() {
        error = null;
        message = null;

        if (payerId == null || receiverId == null) {
            error = "Choose who paid, and who they paid.";
            return;
        }
        // Checked here as well as by the service and by a database constraint,
        // because it is the mistake most likely to be made in this form.
        if (payerId.equals(receiverId)) {
            error = "A repayment needs two different people.";
            return;
        }
        if (amount == null || amount.signum() <= 0) {
            error = "The amount must be greater than zero.";
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
            message = recorded.getPayerName() + " paid " + recorded.getReceiverName()
                    + " NPR " + money(recorded.getAmount()) + ".";
            amount = null;
            note = null;
            refresh();
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    public void delete(Long settlementId) {
        error = null;
        message = null;
        try {
            expenseService.deleteSettlement(settlementId, currentUser.getUserId());
            message = "Repayment removed.";
            refresh();
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
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
        error = null;
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

    public List<TripMemberDTO> getMembers() {
        return members;
    }

    public List<MemberBalanceDTO> getBalances() {
        return balances;
    }

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

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
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
