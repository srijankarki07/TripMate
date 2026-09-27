package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * How much one user has spent in a category, across all their trips.
 *
 * <p>The direct result of complex query 2 (ROADMAP section 9.5). Like
 * {@link MemberBalanceDTO} it has no {@code from(entity)} mapper, because it
 * does not correspond to a row in any table: the database produces it by
 * grouping splits by category.
 *
 * <h2>The constructor signature is pinned by that query</h2>
 *
 * <pre>
 * SELECT new com.tripmate.dto.CategorySpendingDTO(
 *     e.category, SUM(es.amountOwed), COUNT(DISTINCT e.trip.tripId))
 * FROM ExpenseSplit es
 *   JOIN es.expense e
 *   JOIN e.trip t
 *   JOIN t.members tm
 * WHERE tm.user.userId = :userId
 * GROUP BY e.category
 * ORDER BY SUM(es.amountOwed) DESC
 * </pre>
 *
 * <p>{@code tripCount} is a {@link Long} rather than a primitive because JPQL
 * {@code COUNT} yields a {@code Long}, and constructor resolution matches the
 * boxed type exactly. Declaring it {@code long} would make the query fail to
 * find a matching constructor at runtime.
 */
public class CategorySpendingDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String category;
    private BigDecimal totalSpent;
    private Long tripCount;

    public CategorySpendingDTO() {
    }

    /** The constructor the JPQL constructor expression resolves against. */
    public CategorySpendingDTO(String category, BigDecimal totalSpent, Long tripCount) {
        this.category = category;
        this.totalSpent = totalSpent;
        this.tripCount = tripCount;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getTotalSpent() {
        return totalSpent;
    }

    public void setTotalSpent(BigDecimal totalSpent) {
        this.totalSpent = totalSpent;
    }

    /**
     * How many distinct trips contributed to this total. Counted with
     * {@code DISTINCT} because a user can have several expenses in the same
     * category on one trip, and the report answers "how widely did I spend on
     * this", not "how many expenses were there".
     */
    public Long getTripCount() {
        return tripCount;
    }

    public void setTripCount(Long tripCount) {
        this.tripCount = tripCount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CategorySpendingDTO dto)) {
            return false;
        }
        return Objects.equals(category, dto.category)
                && Objects.equals(totalSpent, dto.totalSpent)
                && Objects.equals(tripCount, dto.tripCount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, totalSpent, tripCount);
    }

    @Override
    public String toString() {
        return "CategorySpendingDTO{category='" + category + "', totalSpent=" + totalSpent
                + ", tripCount=" + tripCount + "}";
    }
}
