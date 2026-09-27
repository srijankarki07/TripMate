package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.SplitDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Logging expenses and how they are split.
 *
 * <p>{@link #splitEqually()} does the arithmetic in Java rather than leaving it
 * to the user, because dividing an amount by a group rarely comes out even:
 * 1000.00 across three people is 333.333... each, and three of those do not add
 * up to 1000.00. The remainder is given to the first participant so the parts
 * always total the whole, which is the rule the service enforces on save and a
 * database invariant behind it.
 */
@Named
@ViewScoped
public class ExpenseBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Free text in the schema, offered as a picker that still accepts typing. */
    private static final List<String> CATEGORIES = List.of(
            "TRANSPORT", "ACCOMMODATION", "FOOD", "ACTIVITY",
            "SHOPPING", "EQUIPMENT", "OTHER");

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private Long tripId;
    private TripDTO trip;
    private List<ExpenseDTO> expenses = List.of();
    private List<TripMemberDTO> members = List.of();

    private String description;
    private BigDecimal amount;
    private String category = "FOOD";
    private LocalDate expenseDate = LocalDate.now();
    private Long paidById;
    private List<SplitRow> splits = new ArrayList<>();

    private boolean member;
    private String error;
    private String message;

    /** Set by the redirect that follows a submission, so the page can say so. */
    private String submitted;
    private boolean justSubmitted;

    public void load() {
        error = null;
        justSubmitted = "1".equals(submitted);
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
        paidById = currentUser.getUserId();
        refresh();
        buildSplitRows();
    }

    private void refresh() {
        expenses = expenseService.getExpensesForTrip(tripId);
        members = tripService.getTripMembers(tripId);
    }

    /**
     * One participant row in the split editor.
     *
     * <p>A class with getters rather than a record: EL 5 resolves bean
     * properties only, so a record's {@code name()} accessor is not a property
     * and the table would fail at render time.
     */
    public static final class SplitRow implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Long userId;
        private final String name;
        private boolean included = true;
        private BigDecimal amount;

        public SplitRow(Long userId, String name) {
            this.userId = userId;
            this.name = name;
        }

        public Long getUserId() {
            return userId;
        }

        public String getName() {
            return name;
        }

        public boolean isIncluded() {
            return included;
        }

        public void setIncluded(boolean included) {
            this.included = included;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }

    /** Rebuilds the participant rows from the trip's current membership. */
    private void buildSplitRows() {
        splits = new ArrayList<>();
        for (TripMemberDTO m : members) {
            splits.add(new SplitRow(m.getUserId(), m.getFullName()));
        }
    }

    /**
     * Divides the amount across the included participants.
     *
     * <p>Everyone gets the same figure rounded down to the paisa, and the first
     * participant absorbs the remainder. Without that the parts would sum to
     * slightly less than the amount and the save would be refused.
     */
    public void splitEqually() {
        error = null;
        message = null;

        List<SplitRow> included = includedRows();
        if (amount == null || amount.signum() <= 0) {
            error = "Enter the amount before splitting it.";
            return;
        }
        if (included.isEmpty()) {
            error = "Include at least one person in the split.";
            return;
        }

        BigDecimal count = BigDecimal.valueOf(included.size());
        BigDecimal share = amount.divide(count, 2, RoundingMode.DOWN);
        BigDecimal remainder = amount.subtract(share.multiply(count));

        for (int i = 0; i < included.size(); i++) {
            // The first participant takes the leftover paisa.
            included.get(i).setAmount(i == 0 ? share.add(remainder) : share);
        }
        for (SplitRow row : splits) {
            if (!row.isIncluded()) {
                row.setAmount(null);
            }
        }
    }

    public void clearSplit() {
        for (SplitRow row : splits) {
            row.setAmount(null);
        }
        error = null;
        message = null;
    }

    /** @return a redirect back to the trip once saved, or null to stay */
    public String save() {
        error = null;
        message = null;

        if (description == null || description.isBlank()) {
            error = "Say what the expense was for.";
            return null;
        }
        if (amount == null || amount.signum() <= 0) {
            error = "The amount must be greater than zero.";
            return null;
        }
        if (expenseDate == null) {
            error = "Choose the date the expense was incurred.";
            return null;
        }
        if (paidById == null) {
            error = "Say who paid.";
            return null;
        }

        List<SplitRow> included = includedRows();
        if (included.isEmpty()) {
            error = "Include at least one person in the split.";
            return null;
        }
        if (included.stream().anyMatch(r -> r.getAmount() == null)) {
            error = "Give every included person a share, or split it equally.";
            return null;
        }

        // Checked here as well as by the service, so the user gets a sentence
        // naming the two figures rather than a rejection.
        BigDecimal total = included.stream()
                .map(SplitRow::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(amount) != 0) {
            // Both figures are shown at two decimal places, because "400" next
            // to "1000.00" reads as a different kind of number.
            error = "The shares add up to " + money(total) + ", but the expense is "
                    + money(amount) + ". Adjust them, or use Split equally.";
            return null;
        }

        ExpenseDTO dto = new ExpenseDTO();
        dto.setTripId(tripId);
        dto.setPaidById(paidById);
        dto.setDescription(description.trim());
        dto.setAmount(amount);
        dto.setCategory(category);
        dto.setExpenseDate(expenseDate);
        for (SplitRow row : included) {
            dto.addSplit(new SplitDTO(row.getUserId(), row.getName(), row.getAmount(), false));
        }

        try {
            // Asynchronous: the write happens in ExpenseMessageBean, so the
            // browser is not held while the expense row and one row per
            // participant are written. The service validates before sending, so
            // anything that could never be stored is still refused here, while
            // the user is looking at the form.
            expenseService.submitExpenseAsync(dto, currentUser.getUserId());
            return "/trips/expenses.xhtml?faces-redirect=true&tripId=" + tripId
                    + "&submitted=1";
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
            return null;
        }
    }

    /**
     * Reloads the expense list.
     *
     * <p>Offered as a button because the expense was submitted asynchronously:
     * by the time the page renders the consumer may not have written it yet, so
     * a refresh is the honest way to see it rather than a silent delay.
     */
    public void reload() {
        error = null;
        message = null;
        justSubmitted = false;
        refresh();
    }

    public void delete(Long expenseId) {
        error = null;
        message = null;
        try {
            expenseService.deleteExpense(expenseId, currentUser.getUserId());
            message = "Expense removed.";
            refresh();
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    private List<SplitRow> includedRows() {
        return splits.stream().filter(SplitRow::isIncluded).toList();
    }

    // ------------------------------------------------------------------
    // Derived figures for the form
    // ------------------------------------------------------------------

    /** What the included rows currently add up to. */
    public BigDecimal getSplitTotal() {
        return splits.stream()
                .filter(SplitRow::isIncluded)
                .map(SplitRow::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What is left to assign, so the user can see the gap close. */
    public BigDecimal getUnassigned() {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(getSplitTotal());
    }

    /**
     * Whether to show the "still to assign" line.
     *
     * <p>Only once an amount has been typed. Showing it while the amount is
     * still blank reads as an error before anything has been done.
     */
    public boolean isShowRemainder() {
        return amount != null && amount.signum() > 0 && !isBalanced();
    }

    public boolean isBalanced() {
        return amount != null && amount.signum() > 0
                && amount.compareTo(getSplitTotal()) == 0
                && includedRows().stream().noneMatch(r -> r.getAmount() == null);
    }

    public BigDecimal getExpenseTotal() {
        return expenses.stream()
                .map(ExpenseDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<String> getCategories() {
        return CATEGORIES;
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

    public List<ExpenseDTO> getExpenses() {
        return expenses;
    }

    public List<TripMemberDTO> getMembers() {
        return members;
    }

    public List<SplitRow> getSplits() {
        return splits;
    }

    public boolean isMember() {
        return member;
    }

    public boolean isLoaded() {
        return trip != null;
    }

    public boolean isHasExpenses() {
        return !expenses.isEmpty();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public void setExpenseDate(LocalDate expenseDate) {
        this.expenseDate = expenseDate;
    }

    public Long getPaidById() {
        return paidById;
    }

    public void setPaidById(Long paidById) {
        this.paidById = paidById;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getSubmitted() {
        return submitted;
    }

    public void setSubmitted(String submitted) {
        this.submitted = submitted;
    }

    /** True when the page is being shown straight after a submission. */
    public boolean isJustSubmitted() {
        return justSubmitted;
    }

    /** Money at two decimal places, matching the column's scale. */
    private static String money(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
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
