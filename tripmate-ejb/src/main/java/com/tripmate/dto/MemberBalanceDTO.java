package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What one member of a trip has paid, what they owe, and the difference.
 *
 * <p>This DTO is the direct result of complex query 1 (ROADMAP section 9.4),
 * which is why it has no {@code from(entity)} mapper: it does not correspond to
 * a row in any table. It is assembled inside the database by a JPQL constructor
 * expression, so it carries only the constructor and accessors.
 *
 * <h2>The constructor signature is pinned by that query</h2>
 *
 * The query reads:
 *
 * <pre>
 * SELECT new com.tripmate.dto.MemberBalanceDTO(
 *     u.userId, u.fullName,
 *     (SELECT COALESCE(SUM(e.amount), 0) FROM Expense e
 *       WHERE e.paidBy = u AND e.trip.tripId = :tripId),
 *     (SELECT COALESCE(SUM(es.amountOwed), 0) FROM ExpenseSplit es
 *       WHERE es.user = u AND es.expense.trip.tripId = :tripId))
 * FROM TripMember tm JOIN tm.user u
 * WHERE tm.trip.tripId = :tripId
 * ORDER BY u.fullName
 * </pre>
 *
 * <p>Changing the parameter order or types here breaks that query at runtime,
 * not at compile time, so the two must be edited together. Both sums are
 * declared {@link BigDecimal} to match a {@code DECIMAL(12,2)} column; this is
 * verified when the query is built in Phase 5.
 */
public class MemberBalanceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String fullName;
    private BigDecimal totalPaid;
    private BigDecimal totalOwed;
    private BigDecimal netBalance;

    public MemberBalanceDTO() {
    }

    /**
     * The constructor the JPQL constructor expression resolves against.
     *
     * <p>Derives {@link #netBalance} rather than taking it as an argument, so a
     * caller cannot construct a DTO whose three figures disagree. The ROADMAP
     * describes the service computing the difference after the query; doing it
     * here makes that impossible to get wrong instead of merely documented.
     */
    public MemberBalanceDTO(Long userId, String fullName,
                            BigDecimal totalPaid, BigDecimal totalOwed) {
        this.userId = userId;
        this.fullName = fullName;
        this.totalPaid = totalPaid == null ? BigDecimal.ZERO : totalPaid;
        this.totalOwed = totalOwed == null ? BigDecimal.ZERO : totalOwed;
        this.netBalance = this.totalPaid.subtract(this.totalOwed);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }

    public void setTotalPaid(BigDecimal totalPaid) {
        this.totalPaid = totalPaid;
    }

    public BigDecimal getTotalOwed() {
        return totalOwed;
    }

    public void setTotalOwed(BigDecimal totalOwed) {
        this.totalOwed = totalOwed;
    }

    /**
     * Positive when this member is owed money by the group, negative when they
     * owe the group. Read-only: it is derived from the other two figures.
     */
    public BigDecimal getNetBalance() {
        return netBalance;
    }

    /** True when the member is in credit, which the UI shows as "is owed". */
    public boolean isCreditor() {
        return netBalance != null && netBalance.signum() > 0;
    }

    /** True when the member is in debit, which the UI shows as "owes". */
    public boolean isDebtor() {
        return netBalance != null && netBalance.signum() < 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemberBalanceDTO dto)) {
            return false;
        }
        return Objects.equals(userId, dto.userId)
                && Objects.equals(totalPaid, dto.totalPaid)
                && Objects.equals(totalOwed, dto.totalOwed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, totalPaid, totalOwed);
    }

    @Override
    public String toString() {
        return "MemberBalanceDTO{fullName='" + fullName + "', totalPaid=" + totalPaid
                + ", totalOwed=" + totalOwed + ", netBalance=" + netBalance + "}";
    }
}
