package com.tripmate.ejb;

import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.remote.ReportServiceRemote;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Reporting and analytics.
 *
 * <p>Every method is read-only and builds its result with a JPQL constructor
 * expression, so the DTO is assembled inside the database and nothing is
 * materialised as an entity just to be counted and discarded.
 *
 * <h2>Why these are on a separate bean</h2>
 *
 * They are the only operations that aggregate rather than move rows. Keeping
 * them apart from {@code ExpenseService} means the write path has no reporting
 * queries to accidentally load into a transaction, and the reporting queries
 * can be tuned without touching anything that writes.
 */
@Stateless
public class ReportService implements ReportServiceRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    /**
     * QUERY 4 (ROADMAP section 9.4): net balance per member of a trip.
     *
     * <p>Four entities are involved. TripMember is the driving table because the
     * answer is wanted for every member, including one who has paid nothing and
     * owes nothing; starting from Expense would silently omit them. The two
     * correlated subqueries then total what each member paid and what they owe.
     *
     * <p>The DTO's constructor derives netBalance from the two sums, so the
     * difference is never computed here and cannot disagree with the figures
     * it is shown beside.
     */
    @Override
    public List<MemberBalanceDTO> getMemberBalances(Long tripId) {
        if (tripId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT new com.tripmate.dto.MemberBalanceDTO("
                                + "  u.userId, u.fullName,"
                                + "  (SELECT COALESCE(SUM(e.amount), 0) FROM Expense e"
                                + "     WHERE e.paidBy = u AND e.trip.tripId = :tripId),"
                                + "  (SELECT COALESCE(SUM(es.amountOwed), 0) FROM ExpenseSplit es"
                                + "     WHERE es.user = u AND es.expense.trip.tripId = :tripId))"
                                + " FROM TripMember tm JOIN tm.user u"
                                + " WHERE tm.trip.tripId = :tripId"
                                + " ORDER BY u.fullName",
                        MemberBalanceDTO.class)
                .setParameter("tripId", tripId)
                .getResultList();
    }

    /**
     * QUERY 5 (ROADMAP section 9.5): category spending across a user's trips.
     *
     * <p>Four entities, joined rather than correlated: starting from the split
     * and walking outward is what makes the totals per category, since it is
     * the split that records what a person actually owes.
     *
     * <p>The join to TripMember is what restricts the answer to trips the user
     * belongs to. Grouping happens in the database; doing it in Java would mean
     * loading every split the user has ever incurred.
     */
    @Override
    public List<CategorySpendingDTO> getCategorySpendingForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT new com.tripmate.dto.CategorySpendingDTO("
                                + "  e.category, SUM(es.amountOwed), COUNT(DISTINCT e.trip.tripId))"
                                + " FROM ExpenseSplit es"
                                + "   JOIN es.expense e"
                                + "   JOIN e.trip t"
                                + "   JOIN t.members tm"
                                + " WHERE tm.user.userId = :userId"
                                + " GROUP BY e.category"
                                + " ORDER BY SUM(es.amountOwed) DESC",
                        CategorySpendingDTO.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public BigDecimal getTotalSpentForTrip(Long tripId) {
        if (tripId == null) {
            return BigDecimal.ZERO;
        }
        return scalar("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e "
                + "WHERE e.trip.tripId = :id", tripId);
    }

    @Override
    public BigDecimal getTotalPaidByUser(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        return scalar("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e "
                + "WHERE e.paidBy.userId = :id", userId);
    }

    @Override
    public BigDecimal getTotalOwedByUser(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        return scalar("SELECT COALESCE(SUM(es.amountOwed), 0) FROM ExpenseSplit es "
                + "WHERE es.user.userId = :id", userId);
    }

    @Override
    public long getTripCountForUser(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return em.createQuery(
                        "SELECT COUNT(tm) FROM TripMember tm WHERE tm.user.userId = :id",
                        Long.class)
                .setParameter("id", userId)
                .getSingleResult();
    }

    /** Runs a single-value numeric aggregate, treating a null result as zero. */
    private BigDecimal scalar(String jpql, Long id) {
        BigDecimal value = em.createQuery(jpql, BigDecimal.class)
                .setParameter("id", id)
                .getSingleResult();
        return value == null ? BigDecimal.ZERO : value;
    }
}
