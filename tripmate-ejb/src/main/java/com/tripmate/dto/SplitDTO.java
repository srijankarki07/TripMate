package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

import com.tripmate.entity.ExpenseSplit;

/**
 * One participant's share of an expense, nested inside {@link ExpenseDTO}.
 *
 * <p>Split out as its own type rather than flattened into the parent because
 * the relationship is one expense to many shares: a single {@code ExpenseDTO}
 * carries a list of these.
 *
 * <p>There is no split id. Nothing in the design addresses an individual split
 * by identity; the settle-up flow records a {@link SettlementDTO} between two
 * people, not a change to one share. If a later phase needs to mark a specific
 * share as reimbursed from the UI, an id will have to be added here.
 */
public class SplitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String userName;
    private BigDecimal amountOwed;
    private boolean settled;

    public SplitDTO() {
    }

    public SplitDTO(Long userId, String userName, BigDecimal amountOwed, boolean settled) {
        this.userId = userId;
        this.userName = userName;
        this.amountOwed = amountOwed;
        this.settled = settled;
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>Traverses {@code split.user}, which is a lazy association, so this
     * must be called while the persistence context is still open. Resolving the
     * user once into a local avoids a second lookup for the name.
     */
    public static SplitDTO from(ExpenseSplit split) {
        if (split == null) {
            return null;
        }
        var user = split.getUser();
        return new SplitDTO(
                user == null ? null : user.getUserId(),
                user == null ? null : user.getFullName(),
                split.getAmountOwed(),
                split.isSettled());
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SplitDTO dto)) {
            return false;
        }
        return Objects.equals(userId, dto.userId)
                && Objects.equals(amountOwed, dto.amountOwed)
                && settled == dto.settled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, amountOwed, settled);
    }

    @Override
    public String toString() {
        return "SplitDTO{userId=" + userId + ", userName='" + userName
                + "', amountOwed=" + amountOwed + ", settled=" + settled + "}";
    }
}
