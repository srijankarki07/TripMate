package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.tripmate.entity.Expense;

/**
 * An expense together with who owes what for it.
 *
 * <p>The splits travel inside the expense rather than as a separate call,
 * because an expense is never meaningful without them: the amount says what was
 * spent, the splits say who is on the hook for it. Fetching them separately
 * would mean a half-loaded expense is representable, and the expense form, the
 * expense list and the async write path would each have to remember to ask.
 *
 * <p>{@code amount} stays a {@link BigDecimal} across the boundary. Converting
 * to {@code double} for transport and back would reintroduce exactly the
 * rounding drift the database schema avoids by using {@code DECIMAL(12,2)}.
 */
public class ExpenseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long expenseId;
    private Long tripId;
    private String paidByName;
    private String description;
    private BigDecimal amount;
    private String category;
    private LocalDate expenseDate;
    private List<SplitDTO> splits = new ArrayList<>();

    public ExpenseDTO() {
    }

    public ExpenseDTO(Long expenseId, Long tripId, String paidByName, String description,
                      BigDecimal amount, String category, LocalDate expenseDate,
                      List<SplitDTO> splits) {
        this.expenseId = expenseId;
        this.tripId = tripId;
        this.paidByName = paidByName;
        this.description = description;
        this.amount = amount;
        this.category = category;
        this.expenseDate = expenseDate;
        setSplits(splits);
    }

    /**
     * Maps an entity to its DTO, including every split.
     *
     * <p>Traverses three lazy associations, so it must run inside the business
     * tier with the persistence context open. Returning the entity instead and
     * letting the client walk it is exactly the mistake DTOs exist to prevent:
     * the first {@code getSplits()} call would arrive after the context had
     * closed and fail with a lazy initialisation error.
     */
    public static ExpenseDTO from(Expense expense) {
        if (expense == null) {
            return null;
        }
        var trip = expense.getTrip();
        var payer = expense.getPaidBy();

        List<SplitDTO> mapped = new ArrayList<>();
        for (var split : expense.getSplits()) {
            mapped.add(SplitDTO.from(split));
        }

        return new ExpenseDTO(
                expense.getExpenseId(),
                trip == null ? null : trip.getTripId(),
                payer == null ? null : payer.getFullName(),
                expense.getDescription(),
                expense.getAmount(),
                expense.getCategory(),
                expense.getExpenseDate(),
                mapped);
    }

    public Long getExpenseId() {
        return expenseId;
    }

    public void setExpenseId(Long expenseId) {
        this.expenseId = expenseId;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getPaidByName() {
        return paidByName;
    }

    public void setPaidByName(String paidByName) {
        this.paidByName = paidByName;
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

    public List<SplitDTO> getSplits() {
        return splits;
    }

    /**
     * Replaces the split list. Defensive against null so a deserialised or
     * partially populated DTO never hands back a null collection, which would
     * turn every caller's loop into a null check.
     */
    public void setSplits(List<SplitDTO> splits) {
        this.splits = splits == null ? new ArrayList<>() : new ArrayList<>(splits);
    }

    /** Adds one split, keeping the list non-null. */
    public void addSplit(SplitDTO split) {
        if (split != null) {
            this.splits.add(split);
        }
    }

    /**
     * What the splits currently add up to.
     *
     * <p>Used by the expense form to show a running total against the amount,
     * and by the business tier to check the rule that the two must agree.
     */
    public BigDecimal getSplitTotal() {
        return splits.stream()
                .map(SplitDTO::getAmountOwed)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** True when the splits reconcile with the amount, to the paisa. */
    public boolean isBalanced() {
        return amount != null && amount.compareTo(getSplitTotal()) == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExpenseDTO dto)) {
            return false;
        }
        return expenseId != null && expenseId.equals(dto.expenseId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(expenseId);
    }

    @Override
    public String toString() {
        return "ExpenseDTO{expenseId=" + expenseId + ", description='" + description
                + "', amount=" + amount + ", splits=" + splits.size() + "}";
    }
}
