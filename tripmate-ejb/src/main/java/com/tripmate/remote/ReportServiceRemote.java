package com.tripmate.remote;

import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.dto.CategoryTotalDTO;
import com.tripmate.dto.DailyTotalDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.TripTotalDTO;

import jakarta.ejb.Remote;

/**
 * Reporting and analytics.
 *
 * <p>Read-only. Every method here answers a question about data other services
 * wrote, which is why there is no create, update or delete on this interface.
 *
 * <p>The two query methods are the complex queries the assignment asks for:
 * each navigates more than three related entities in a single JPQL statement
 * and lets the database do the aggregation.
 */
@Remote
public interface ReportServiceRemote {

    /**
     * Query 4 (ROADMAP section 9.4): what each member of a trip has paid,
     * what they owe, and the difference.
     *
     * <p>Navigates TripMember to User, with correlated subqueries over Expense
     * and ExpenseSplit.
     *
     * <p>The figures are the <strong>gross</strong> position: every split is
     * counted regardless of whether it has been marked settled, and repayments
     * recorded in the settlements table are not netted off. This answers "who
     * is up and who is down on this trip", which is what the balances tab
     * shows. {@code ExpenseService.getSettlementsForTrip} answers the different
     * question of what has already been repaid.
     *
     * @return one row per member, ordered by name; empty if the trip has none
     */
    List<MemberBalanceDTO> getMemberBalances(Long tripId, Long actedBy);

    /**
     * Query 5 (ROADMAP section 9.5): how much a user has spent in each
     * category, across every trip they belong to.
     *
     * <p>Navigates ExpenseSplit, Expense, Trip and TripMember, grouping in the
     * database rather than in Java.
     *
     * <p>{@code tripCount} counts distinct trips, so a user with four food
     * expenses on one trip sees one, not four.
     *
     * @return one row per category, largest total first; empty if the user has
     *         no expenses
     */
    List<CategorySpendingDTO> getCategorySpendingForUser(Long userId, Long actedBy);

    // ------------------------------------------------------------------
    // Simple aggregates
    // ------------------------------------------------------------------

    /** @return the sum of a trip's expenses, zero if it has none */
    BigDecimal getTotalSpentForTrip(Long tripId, Long actedBy);

    /** @return everything the user has paid out across all their trips */
    BigDecimal getTotalPaidByUser(Long userId, Long actedBy);

    /** @return everything the user owes across all their trips */
    BigDecimal getTotalOwedByUser(Long userId, Long actedBy);

    /**
     * Everything the user has paid out in recorded repayments, across every
     * trip.
     *
     * <p>Needed because the profile's net position was gross: it compared what
     * the user had paid against their share and ignored the repayments they had
     * already made, so recording one changed nothing anywhere the user could
     * see it.
     */
    BigDecimal getTotalRepaidOutByUser(Long userId, Long actedBy);

    /** @return everything the user has received in recorded repayments */
    BigDecimal getTotalReceivedByUser(Long userId, Long actedBy);

    /** @return how many trips the user belongs to */
    long getTripCountForUser(Long userId, Long actedBy);

    /**
     * What a trip spent in each category, largest first.
     *
     * @param actedBy the user asking, who must be a member of the trip
     */
    List<CategoryTotalDTO> getCategoryTotalsForTrip(Long tripId, Long actedBy);

    /**
     * What a trip spent on each day it spent anything, oldest first.
     *
     * @param actedBy the user asking, who must be a member of the trip
     */
    List<DailyTotalDTO> getDailyTotalsForTrip(Long tripId, Long actedBy);

    /**
     * Every trip a user belongs to, with what it cost and what that is per
     * head, for comparing them against each other.
     */
    List<TripTotalDTO> getTripTotalsForUser(Long userId);

}
