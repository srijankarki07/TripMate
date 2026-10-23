package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one trip spent on one day.
 *
 * <p>The series behind the spend-over-time chart. Days with no spending do not
 * appear at all: the query groups what exists rather than generating a row per
 * calendar day, so the caller is responsible for placing the points on a
 * continuous axis if it wants one.
 *
 * <p>Assembled by a JPQL constructor expression, so it has no
 * {@code from(entity)} mapper.
 */
public class DailyTotalDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private LocalDate day;
    private BigDecimal total;
    private Long expenseCount;

    public DailyTotalDTO() {
    }

    public DailyTotalDTO(LocalDate day, BigDecimal total, Long expenseCount) {
        this.day = day;
        this.total = total;
        this.expenseCount = expenseCount;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public Long getExpenseCount() {
        return expenseCount;
    }

    public void setExpenseCount(Long expenseCount) {
        this.expenseCount = expenseCount;
    }
}
