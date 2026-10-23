package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * What one trip spent in one category.
 *
 * <p>Distinct from {@link CategorySpendingDTO}, which answers a different
 * question: that one is scoped to a person across every trip they belong to,
 * and its {@code tripCount} counts the trips a category appears in. This one is
 * scoped to a single trip and counts the expenses instead, which is what a
 * reader looking at one trip's breakdown wants to know.
 *
 * <p>Assembled by a JPQL constructor expression, so it has no
 * {@code from(entity)} mapper. The constructor signature is pinned by that
 * query and the two must be edited together.
 */
public class CategoryTotalDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String category;
    private BigDecimal total;
    private Long expenseCount;

    public CategoryTotalDTO() {
    }

    public CategoryTotalDTO(String category, BigDecimal total, Long expenseCount) {
        this.category = category;
        this.total = total;
        this.expenseCount = expenseCount;
    }

    /** @return the category, or null when the expenses were recorded without one */
    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    /** @return how many expenses make up this total */
    public Long getExpenseCount() {
        return expenseCount;
    }

    public void setExpenseCount(Long expenseCount) {
        this.expenseCount = expenseCount;
    }

    /**
     * @return the category, or "Uncategorised" when none was recorded
     *
     * <p>The category column is free text with no constraint, so a null is
     * possible. Returning a word rather than a null keeps a chart legend and a
     * table cell from rendering empty.
     */
    public String getLabel() {
        return category == null || category.isBlank() ? "Uncategorised" : category;
    }
}
