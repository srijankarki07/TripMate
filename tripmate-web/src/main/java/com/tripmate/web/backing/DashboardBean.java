package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.entity.TripStatus;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The dashboard: what the signed-in user is part of and what it has cost them.
 *
 * <p>Request-scoped, so a page refresh always shows current figures rather than
 * a snapshot taken when the session began.
 */
@Named
@RequestScoped
public class DashboardBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Enough to show a pattern without turning the dashboard into a ledger. */
    private static final int RECENT_LIMIT = 8;

    @EJB
    private TripServiceRemote tripService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private ReportServiceRemote reportService;

    @Inject
    private CurrentUser currentUser;

    private List<TripDTO> trips = List.of();
    private List<RecentExpense> recentExpenses = List.of();
    private BigDecimal totalPaid = BigDecimal.ZERO;
    private BigDecimal totalOwed = BigDecimal.ZERO;

    @PostConstruct
    void load() {
        Long userId = currentUser.getUserId();
        if (userId == null) {
            // The filter should have caught this; leaving the page empty is
            // better than throwing if it ever does not.
            return;
        }

        trips = tripService.getTripsForUser(userId);
        totalPaid = reportService.getTotalPaidByUser(userId);
        totalOwed = reportService.getTotalOwedByUser(userId);
        recentExpenses = collectRecentExpenses();
    }

    /**
     * Gathers the newest expenses across every trip the user belongs to.
     *
     * <p>One call per trip, which is the N+1 pattern. At three trips it costs
     * nothing and reuses the existing query 2 rather than adding a sixth query
     * to the reporting service for a dashboard list. It would need replacing
     * with a single query if a user could belong to dozens of trips.
     */
    private List<RecentExpense> collectRecentExpenses() {
        return trips.stream()
                .flatMap(trip -> expenseService.getExpensesForTrip(trip.getTripId()).stream()
                        .map(expense -> new RecentExpense(
                                expense.getExpenseId(),
                                trip.getTripName(),
                                expense.getDescription(),
                                expense.getAmount(),
                                expense.getExpenseDate(),
                                expense.getPaidByName())))
                .sorted(Comparator.comparing(RecentExpense::getDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECENT_LIMIT)
                .toList();
    }

    // ------------------------------------------------------------------
    // Figures shown in the summary row
    // ------------------------------------------------------------------

    public int getTripCount() {
        return trips.size();
    }

    public int getActiveTripCount() {
        return (int) trips.stream()
                .filter(t -> t.getStatus() == TripStatus.ONGOING
                        || t.getStatus() == TripStatus.PLANNING)
                .count();
    }

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }

    public BigDecimal getTotalOwed() {
        return totalOwed;
    }

    /**
     * Positive means the user has paid out more than their share and is owed
     * money back; negative means the opposite.
     */
    public BigDecimal getNetPosition() {
        return totalPaid.subtract(totalOwed);
    }

    public boolean isInCredit() {
        return getNetPosition().signum() > 0;
    }

    public String getNetLabel() {
        int sign = getNetPosition().signum();
        if (sign > 0) {
            return "You are owed";
        }
        if (sign < 0) {
            return "You owe";
        }
        return "All square";
    }

    /** The absolute value, since the label already carries the direction. */
    public BigDecimal getNetMagnitude() {
        return getNetPosition().abs();
    }

    public boolean isHasTrips() {
        return !trips.isEmpty();
    }

    public List<TripDTO> getTrips() {
        return trips;
    }

    public List<RecentExpense> getRecentExpenses() {
        return recentExpenses;
    }

    public boolean isHasRecentExpenses() {
        return !recentExpenses.isEmpty();
    }

    /**
     * A row of the recent activity list.
     *
     * <p>Flattens the trip name onto the expense, because {@code ExpenseDTO}
     * carries only the trip id and the list shows which trip each expense
     * belongs to.
     *
     * <p>Written as a class with getters rather than a record. A record's
     * accessors are {@code date()} rather than {@code getDate()}, and EL 5,
     * which is what Faces 4.0 ships, resolves properties by the JavaBean
     * convention only. A record here fails at render time with "does not have
     * the property".
     */
    public static final class RecentExpense implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Long expenseId;
        private final String tripName;
        private final String description;
        private final BigDecimal amount;
        private final LocalDate date;
        private final String paidByName;

        public RecentExpense(Long expenseId, String tripName, String description,
                             BigDecimal amount, LocalDate date, String paidByName) {
            this.expenseId = expenseId;
            this.tripName = tripName;
            this.description = description;
            this.amount = amount;
            this.date = date;
            this.paidByName = paidByName;
        }

        public Long getExpenseId() {
            return expenseId;
        }

        public String getTripName() {
            return tripName;
        }

        public String getDescription() {
            return description;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public LocalDate getDate() {
            return date;
        }

        public String getPaidByName() {
            return paidByName;
        }
    }
}
