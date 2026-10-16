package com.tripmate.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A single cost incurred during a trip, paid up front by one member.
 *
 * <p>Maps to the {@code expenses} table. The amount is a {@link BigDecimal}
 * because a balance is a running sum of many small values: binary floating
 * point cannot represent 0.10 exactly, and the drift eventually stops the
 * splits reconciling with the total.
 *
 * <p>The participants and what each of them owes live in {@link ExpenseSplit},
 * not here. One expense has many shares, and a share is per-person, so it
 * cannot be a column on this table.
 */
@Entity
@Table(name = "expenses")
public class Expense implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "expenseSeq")
    @SequenceGenerator(
            name = "expenseSeq",
            sequenceName = "expenses_expense_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "expense_id", nullable = false)
    private Long expenseId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /**
     * Nullable to match the schema's {@code ON DELETE SET NULL}. An expense
     * records that money was spent; deleting the user who fronted it must not
     * erase the spend from the trip's history.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by")
    private User paidBy;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * The one collection in the model that carries cascade and orphan removal.
     *
     * <p>A split has no life of its own: it exists only to say what one person
     * owes for this expense. Persisting or deleting the expense should carry
     * its splits with it, and removing a split from this list should delete its
     * row rather than leave an orphan. That is precisely what
     * {@code CascadeType.ALL} plus {@code orphanRemoval} express.
     */
    @OneToMany(
            mappedBy = "expense",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<ExpenseSplit> splits = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getExpenseId() {
        return expenseId;
    }

    public void setExpenseId(Long expenseId) {
        this.expenseId = expenseId;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public User getPaidBy() {
        return paidBy;
    }

    public void setPaidBy(User paidBy) {
        this.paidBy = paidBy;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<ExpenseSplit> getSplits() {
        return splits;
    }

    // ------------------------------------------------------------------
    // Bidirectional helpers
    // ------------------------------------------------------------------

    public void addSplit(ExpenseSplit split) {
        splits.add(split);
        split.setExpense(this);
    }

    /**
     * Removing from the collection is enough: {@code orphanRemoval = true}
     * turns the removal into a delete on flush.
     */
    public void removeSplit(ExpenseSplit split) {
        splits.remove(split);
        split.setExpense(null);
    }

    // ------------------------------------------------------------------
    // Derived values
    // ------------------------------------------------------------------

    /**
     * What the splits currently add up to.
     *
     * <p>The business rule is that this must equal {@link #getAmount()}
     * (ROADMAP §8.3). This does not enforce it — {@code ExpenseService} does,
     * so the caller gets a readable message rather than a failed flush — but it
     * gives the service and the tests somewhere to ask the question.
     *
     * <p>Only correct for a loaded collection; on a lazy, uninitialised list
     * this would trigger a load.
     */
    public BigDecimal getSplitTotal() {
        return splits.stream()
                .map(ExpenseSplit::getAmountOwed)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** True when the splits reconcile with the amount, to the paisa. */
    public boolean isBalanced() {
        return amount != null && amount.compareTo(getSplitTotal()) == 0;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Expense expense)) {
            return false;
        }
        return expenseId != null && expenseId.equals(expense.expenseId);
    }

    @Override
    public int hashCode() {
        return Expense.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "Expense{expenseId=" + expenseId + ", description='" + description
                + "', amount=" + amount + "}";
    }
}
