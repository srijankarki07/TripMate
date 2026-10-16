package com.tripmate.entity;

import java.io.Serializable;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One participant's share of an {@link Expense}.
 *
 * <p>Maps to the {@code expense_splits} table. Together the splits of an
 * expense say who owes what; the whole balance calculation in query 4
 * (ROADMAP §9.4) is a sum over this table grouped by user.
 *
 * <p>Note there is no {@code @PrePersist}: this table has no timestamp. The
 * {@code is_settled} flag is the only lifecycle state it carries.
 */
@Entity
@Table(
        name = "expense_splits",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_expense_splits",
                columnNames = {"expense_id", "user_id"}))
public class ExpenseSplit implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "expenseSplitSeq")
    @SequenceGenerator(
            name = "expenseSplitSeq",
            sequenceName = "expense_splits_split_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "split_id", nullable = false)
    private Long splitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "amount_owed", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountOwed;

    /**
     * Whether this participant has reimbursed the payer for this share.
     *
     * <p>Distinct from a {@link Settlement}, which records an actual transfer.
     * Query 4 sums every split regardless of this flag, so balances are the
     * gross position; this flag and the settlements reduce what is still
     * outstanding. Defaulted in the field as well as the schema so a
     * newly-built entity never carries null into a NOT NULL column.
     */
    @Column(name = "is_settled", nullable = false)
    private boolean settled = false;

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getSplitId() {
        return splitId;
    }

    public void setSplitId(Long splitId) {
        this.splitId = splitId;
    }

    public Expense getExpense() {
        return expense;
    }

    public void setExpense(Expense expense) {
        this.expense = expense;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public BigDecimal getAmountOwed() {
        return amountOwed;
    }

    public void setAmountOwed(BigDecimal amountOwed) {
        this.amountOwed = amountOwed;
    }

    public boolean isSettled() {
        return settled;
    }

    public void setSettled(boolean settled) {
        this.settled = settled;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExpenseSplit split)) {
            return false;
        }
        return splitId != null && splitId.equals(split.splitId);
    }

    @Override
    public int hashCode() {
        return ExpenseSplit.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "ExpenseSplit{splitId=" + splitId + ", amountOwed=" + amountOwed
                + ", settled=" + settled + "}";
    }
}
