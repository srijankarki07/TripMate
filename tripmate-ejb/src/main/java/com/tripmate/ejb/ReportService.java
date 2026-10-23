package com.tripmate.ejb;

import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.dto.CategoryTotalDTO;
import com.tripmate.dto.DailyTotalDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.TripTotalDTO;
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
    public List<MemberBalanceDTO> getMemberBalances(Long tripId, Long actedBy) {
        if (tripId == null) {
            return List.of();
        }
        TripAccess.requireMember(em, tripId, actedBy, "read this trip's balances");
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
     * QUERY 5 (ROADMAP section 9.5): category spending for one user.
     *
     * <p>Four entities are joined: ExpenseSplit, Expense, Trip and TripMember.
     * The join to TripMember keeps the navigation the assignment asks for and
     * doubles as a membership guard, so a split only counts if the user is on
     * the trip it belongs to.
     *
     * <h2>Correction to the ROADMAP's version of this query</h2>
     *
     * Section 9.5 filters on {@code tm.user.userId}, the member reached through
     * the join, and sums every split it finds. That measures something else:
     * the total the <em>group</em> spent on the trips the user was part of. For
     * a user who is on every trip it returns the entire database, and for the
     * seeded data user 1 gets 160,300.00 against 41,135.00 for their own share.
     *
     * <p>The report is called "Where Your Money Went", so the split's own owner
     * is what it has to measure. Filtering on {@code es.user.userId} as well
     * keeps the row count right: the join to members would otherwise multiply
     * each split once per member of the trip.
     *
     * <p>This follows the ROADMAP's own guiding principle in section 21: where
     * a change makes the application more useful to real users without breaking
     * the assignment requirement, choose it. The requirement, navigating three
     * or more related entities, is still met.
     */
    @Override
    public List<CategorySpendingDTO> getCategorySpendingForUser(Long userId, Long actedBy) {
        if (userId == null) {
            return List.of();
        }
        requireSelf(userId, actedBy, "read this spending");
        return em.createQuery(
                        "SELECT new com.tripmate.dto.CategorySpendingDTO("
                                + "  e.category, SUM(es.amountOwed), COUNT(DISTINCT e.trip.tripId))"
                                + " FROM ExpenseSplit es"
                                + "   JOIN es.expense e"
                                + "   JOIN e.trip t"
                                + "   JOIN t.members tm"
                                + " WHERE es.user.userId = :userId"
                                + "   AND tm.user.userId = :userId"
                                + " GROUP BY e.category"
                                + " ORDER BY SUM(es.amountOwed) DESC",
                        CategorySpendingDTO.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public BigDecimal getTotalSpentForTrip(Long tripId, Long actedBy) {
        if (tripId == null) {
            return BigDecimal.ZERO;
        }
        TripAccess.requireMember(em, tripId, actedBy, "read this trip's spending");
        return scalar("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e "
                + "WHERE e.trip.tripId = :id", tripId);
    }

    @Override
    public BigDecimal getTotalPaidByUser(Long userId, Long actedBy) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        requireSelf(userId, actedBy, "read these totals");
        return scalar("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e "
                + "WHERE e.paidBy.userId = :id", userId);
    }

    @Override
    public BigDecimal getTotalOwedByUser(Long userId, Long actedBy) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        requireSelf(userId, actedBy, "read these totals");
        return scalar("SELECT COALESCE(SUM(es.amountOwed), 0) FROM ExpenseSplit es "
                + "WHERE es.user.userId = :id", userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every settlement names a payer directly, so this needs no join through
     * the trip membership: a repayment the user made is theirs whichever trip
     * it was on.
     */
    @Override
    public BigDecimal getTotalRepaidOutByUser(Long userId, Long actedBy) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        requireSelf(userId, actedBy, "read these totals");
        return scalar("SELECT COALESCE(SUM(s.amount), 0) FROM Settlement s "
                + "WHERE s.payer.userId = :id", userId);
    }

    /** {@inheritDoc} */
    @Override
    public BigDecimal getTotalReceivedByUser(Long userId, Long actedBy) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        requireSelf(userId, actedBy, "read these totals");
        return scalar("SELECT COALESCE(SUM(s.amount), 0) FROM Settlement s "
                + "WHERE s.receiver.userId = :id", userId);
    }

    @Override
    public long getTripCountForUser(Long userId, Long actedBy) {
        if (userId == null) {
            return 0L;
        }
        requireSelf(userId, actedBy, "read these totals");
        return em.createQuery(
                        "SELECT COUNT(tm) FROM TripMember tm WHERE tm.user.userId = :id",
                        Long.class)
                .setParameter("id", userId)
                .getSingleResult();
    }

    /** Runs a single-value numeric aggregate, treating a null result as zero. */
    /**
     * Refuses a request for someone else's totals.
     *
     * <p>The user-scoped aggregates took a bare user id, so any caller could
     * ask for any user's spending by guessing a number. The web tier always
     * passed the signed-in user, but the service was open. Reading your own
     * figures is the only thing these are for, so the actor must be the subject.
     */
    private static void requireSelf(Long userId, Long actedBy, String what) {
        if (actedBy == null || !actedBy.equals(userId)) {
            throw new ValidationException("you can only " + what + " on your own account");
        }
    }

    private BigDecimal scalar(String jpql, Long id) {
        BigDecimal value = em.createQuery(jpql, BigDecimal.class)
                .setParameter("id", id)
                .getSingleResult();
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * What a trip spent in each category.
     *
     * <p>Sums the whole expense rather than the splits, because the question is
     * what the trip spent, not what any one person's share of it was.
     *
     * <p>Guarded, unlike the reporting methods that predate it: a breakdown of
     * a trip's spending is not something a stranger should be able to read by
     * guessing a trip id.
     */
    @Override
    public List<CategoryTotalDTO> getCategoryTotalsForTrip(Long tripId, Long actedBy) {
        if (tripId == null) {
            return List.of();
        }
        TripAccess.requireMember(em, tripId, actedBy, "read this trip's spending");
        return em.createQuery(
                        "SELECT new com.tripmate.dto.CategoryTotalDTO("
                                + "  e.category, SUM(e.amount), COUNT(e))"
                                + " FROM Expense e"
                                + " WHERE e.trip.tripId = :tripId"
                                + " GROUP BY e.category"
                                + " ORDER BY SUM(e.amount) DESC",
                        CategoryTotalDTO.class)
                .setParameter("tripId", tripId)
                .getResultList();
    }

    /**
     * What a trip spent on each day.
     *
     * <p>Days with no spending are absent rather than zero. Generating a row
     * per calendar day would be a date series the database has no natural way
     * to produce, and a gap in a line is a truthful picture of a day nobody
     * bought anything.
     */
    @Override
    public List<DailyTotalDTO> getDailyTotalsForTrip(Long tripId, Long actedBy) {
        if (tripId == null) {
            return List.of();
        }
        TripAccess.requireMember(em, tripId, actedBy, "read this trip's spending");
        return em.createQuery(
                        "SELECT new com.tripmate.dto.DailyTotalDTO("
                                + "  e.expenseDate, SUM(e.amount), COUNT(e))"
                                + " FROM Expense e"
                                + " WHERE e.trip.tripId = :tripId"
                                + " GROUP BY e.expenseDate"
                                + " ORDER BY e.expenseDate",
                        DailyTotalDTO.class)
                .setParameter("tripId", tripId)
                .getResultList();
    }

    /**
     * Every trip a user belongs to, with its total and its cost per head.
     *
     * <p>Driven from Trip joined to its members so a trip the user belongs to
     * appears even when nothing has been spent on it yet; starting from Expense
     * would quietly drop it, and a trip that has cost nothing is a fact worth
     * showing rather than an absence.
     */
    @Override
    public List<TripTotalDTO> getTripTotalsForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT new com.tripmate.dto.TripTotalDTO("
                                + "  t.tripId, t.tripName,"
                                + "  (SELECT COALESCE(SUM(e.amount), 0) FROM Expense e"
                                + "     WHERE e.trip = t),"
                                + "  t.startDate, t.status,"
                                + "  (SELECT COUNT(tm) FROM TripMember tm WHERE tm.trip = t))"
                                + " FROM Trip t JOIN t.members m"
                                + " WHERE m.user.userId = :userId"
                                + " ORDER BY t.startDate DESC",
                        TripTotalDTO.class)
                .setParameter("userId", userId)
                .getResultList();
    }
}
