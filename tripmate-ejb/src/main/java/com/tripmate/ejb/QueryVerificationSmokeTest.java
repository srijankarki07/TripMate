package com.tripmate.ejb;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Verifies the five required queries by comparing each against the same
 * question asked in raw SQL.
 *
 * <h2>Why raw SQL is the right yardstick</h2>
 *
 * A query cannot be checked against itself. Asserting that
 * {@code getMemberBalances} returns what the developer expected by reading the
 * JPQL would only confirm that the same misunderstanding appears in both
 * places. The expected values here are computed by native SQL that goes
 * straight to PostgreSQL with no entity mapping, no association resolution and
 * no JPQL translation involved. If the object path {@code es.expense.trip.tripId}
 * resolves to the wrong join, or a fetch silently misses rows, the two answers
 * differ.
 *
 * <h2>What is checked, and how</h2>
 *
 * Every trip and every user in the database is checked, not just the
 * demonstration ones, so a query that happens to be right for one group size is
 * not mistaken for a correct query.
 *
 * <p>Ordering is verified in two pieces rather than by comparing ordered lists
 * directly. The <em>set</em> of rows is compared against SQL, which proves the
 * filter and the navigation; the <em>order</em> is then checked as a property of
 * the returned sequence. Comparing ordered lists would make the check flaky
 * whenever two rows share a sort key, because SQL leaves their relative order
 * unspecified and either answer would be legitimate.
 */
@Stateless
public class QueryVerificationSmokeTest {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    @EJB
    private ItineraryServiceRemote itineraryService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private TripServiceRemote tripService;

    @EJB
    private ReportServiceRemote reportService;

    public List<String> runQueryChecks() {
        List<String> report = new ArrayList<>();

        List<Long> tripIds = nativeIds("SELECT trip_id FROM trips ORDER BY trip_id");
        List<Long> userIds = nativeIds("SELECT user_id FROM users ORDER BY user_id");

        checkQuery1(report, tripIds);
        checkQuery2(report, tripIds);
        checkQuery3(report, userIds);
        checkQuery4(report, tripIds);
        checkQuery5(report, userIds);
        checkInvariants(report, tripIds);

        long failures = report.stream().filter(line -> line.startsWith("FAIL")).count();
        report.add(failures == 0
                ? "SUMMARY: all " + report.size() + " query checks passed"
                : "SUMMARY: " + failures + " of " + report.size() + " query checks FAILED");
        return report;
    }

    // ==================================================================
    // Query 1: itinerary for a trip, ordered by day then start time
    // ==================================================================

    private void checkQuery1(List<String> report, List<Long> tripIds) {
        for (Long tripId : tripIds) {
            List<ItineraryItemDTO> actual = itineraryService.getItineraryForTrip(tripId);

            List<Long> expected = nativeIds(
                    "SELECT item_id FROM itinerary_items WHERE trip_id = ?1 "
                            + "ORDER BY item_date, start_time", tripId);

            check(report, "Q1", "trip " + tripId + ": returns the same rows as SQL",
                    sameIds(expected, actual.stream().map(ItineraryItemDTO::getItemId).toList()));

            check(report, "Q1", "trip " + tripId + ": ordered by day then start time",
                    sortedBy(actual, item -> new CompositeKey(item.getItemDate(), item.getStartTime()), false));

            // The dates themselves must also be right, not just their order.
            check(report, "Q1", "trip " + tripId + ": item dates match SQL",
                    sameValues(
                            nativeStrings("SELECT item_date::text FROM itinerary_items "
                                    + "WHERE trip_id = ?1 ORDER BY item_id", tripId),
                            actual.stream()
                                    .sorted((a, b) -> Long.compare(a.getItemId(), b.getItemId()))
                                    .map(i -> i.getItemDate().toString())
                                    .toList()));
        }

        // The demonstration trip is known to have six items; a query returning
        // nothing would otherwise pass every check above.
        Long pokhara = pokharaTripId();
        if (pokhara != null) {
            check(report, "Q1", "the demonstration trip returns its six seeded items",
                    itineraryService.getItineraryForTrip(pokhara).size() == 6);
        }
    }

    // ==================================================================
    // Query 2: expenses for a trip, newest first
    // ==================================================================

    private void checkQuery2(List<String> report, List<Long> tripIds) {
        for (Long tripId : tripIds) {
            List<ExpenseDTO> actual = expenseService.getExpensesForTrip(tripId);

            List<Long> expected = nativeIds(
                    "SELECT expense_id FROM expenses WHERE trip_id = ?1 "
                            + "ORDER BY expense_date DESC, expense_id DESC", tripId);

            check(report, "Q2", "trip " + tripId + ": returns the same rows as SQL",
                    sameIds(expected, actual.stream().map(ExpenseDTO::getExpenseId).toList()));

            check(report, "Q2", "trip " + tripId + ": ordered newest first",
                    sortedBy(actual, e -> new CompositeKey(e.getExpenseDate(), e.getExpenseId()), true));

            // A fetch join against a collection can multiply rows, which is what
            // DISTINCT is there to prevent. This is the check that would catch
            // it coming back.
            check(report, "Q2", "trip " + tripId + ": no expense is returned twice",
                    actual.size() == actual.stream().map(ExpenseDTO::getExpenseId).distinct().count());

            check(report, "Q2", "trip " + tripId + ": each expense carries its splits",
                    actual.stream().allMatch(e -> {
                        long fromSql = countSplits(e.getExpenseId());
                        return e.getSplits().size() == fromSql;
                    }));

            // The totals the DTO reports must match what SQL sums, which checks
            // the amount and the splits rather than just the row count.
            BigDecimal expectedTotal = nativeDecimal(
                    "SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE trip_id = ?1", tripId);
            BigDecimal actualTotal = actual.stream()
                    .map(ExpenseDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            check(report, "Q2", "trip " + tripId + ": amounts sum to what SQL sums",
                    expectedTotal.compareTo(actualTotal) == 0);
        }
    }

    // ==================================================================
    // Query 3: trips a user belongs to
    // ==================================================================

    private void checkQuery3(List<String> report, List<Long> userIds) {
        for (Long userId : userIds) {
            List<TripDTO> actual = tripService.getTripsForUser(userId);

            List<Long> expected = nativeIds(
                    "SELECT t.trip_id FROM trips t "
                            + "JOIN trip_members m ON m.trip_id = t.trip_id "
                            + "WHERE m.user_id = ?1 ORDER BY t.start_date DESC", userId);

            check(report, "Q3", "user " + userId + ": returns the same trips as SQL",
                    sameIds(expected, actual.stream().map(TripDTO::getTripId).toList()));

            check(report, "Q3", "user " + userId + ": ordered by start date, newest first",
                    sortedBy(actual, TripDTO::getStartDate, true));

            // The member count is the part most likely to be wrong, since it
            // comes from a separate grouped query rather than from the trips.
            boolean countsMatch = true;
            for (TripDTO trip : actual) {
                long fromSql = nativeLong(
                        "SELECT COUNT(*) FROM trip_members WHERE trip_id = ?1", trip.getTripId());
                if (trip.getMemberCount() != fromSql) {
                    countsMatch = false;
                }
            }
            check(report, "Q3", "user " + userId + ": member counts match SQL", countsMatch);

            // Membership, not authorship, is what qualifies a trip.
            check(report, "Q3", "user " + userId + ": every trip is one they belong to",
                    actual.stream().allMatch(t -> tripService.isMember(t.getTripId(), userId)));
        }

        check(report, "Q3", "a user who belongs to no trip gets an empty list, not an error",
                tripService.getTripsForUser(-1L).isEmpty());
    }

    // ==================================================================
    // Query 4: net balance per member of a trip
    // ==================================================================

    private void checkQuery4(List<String> report, List<Long> tripIds) {
        for (Long tripId : tripIds) {
            List<MemberBalanceDTO> actual = reportService.getMemberBalances(tripId, memberOf(tripId));

            // The same question in raw SQL, with the correlated subqueries
            // written out by hand rather than translated from JPQL.
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT u.user_id, u.full_name,"
                                    + "  COALESCE((SELECT SUM(e.amount) FROM expenses e"
                                    + "            WHERE e.paid_by = u.user_id AND e.trip_id = ?1), 0),"
                                    + "  COALESCE((SELECT SUM(s.amount_owed) FROM expense_splits s"
                                    + "            JOIN expenses e2 ON e2.expense_id = s.expense_id"
                                    + "            WHERE s.user_id = u.user_id AND e2.trip_id = ?1), 0)"
                                    + " FROM trip_members tm JOIN users u ON u.user_id = tm.user_id"
                                    + " WHERE tm.trip_id = ?1 ORDER BY u.full_name")
                    .setParameter(1, tripId)
                    .getResultList();

            Map<Long, Object[]> expected = new LinkedHashMap<>();
            for (Object[] row : rows) {
                expected.put(((Number) row[0]).longValue(), row);
            }

            check(report, "Q4", "trip " + tripId + ": one row per member, no more and no fewer",
                    sameIds(new ArrayList<>(expected.keySet()),
                            actual.stream().map(MemberBalanceDTO::getUserId).toList()));

            check(report, "Q4", "trip " + tripId + ": ordered by name",
                    sortedBy(actual, MemberBalanceDTO::getFullName, false));

            boolean paidMatches = true;
            boolean owedMatches = true;
            boolean netMatches = true;
            for (MemberBalanceDTO balance : actual) {
                Object[] row = expected.get(balance.getUserId());
                if (row == null) {
                    paidMatches = owedMatches = netMatches = false;
                    continue;
                }
                BigDecimal paid = decimal(row[2]);
                BigDecimal owed = decimal(row[3]);
                if (paid.compareTo(balance.getTotalPaid()) != 0) {
                    paidMatches = false;
                }
                if (owed.compareTo(balance.getTotalOwed()) != 0) {
                    owedMatches = false;
                }
                if (paid.subtract(owed).compareTo(balance.getNetBalance()) != 0) {
                    netMatches = false;
                }
            }
            check(report, "Q4", "trip " + tripId + ": total paid matches SQL", paidMatches);
            check(report, "Q4", "trip " + tripId + ": total owed matches SQL", owedMatches);
            check(report, "Q4", "trip " + tripId + ": net balance is paid minus owed", netMatches);

            // The defining property of a balanced ledger: every paisa someone is
            // owed comes from someone who owes it. This holds whatever the
            // queries do, so it catches a whole class of error on its own.
            BigDecimal net = actual.stream()
                    .map(MemberBalanceDTO::getNetBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            check(report, "Q4", "trip " + tripId + ": net balances sum to exactly zero",
                    net.signum() == 0);

            // Cross-check against a figure computed a completely different way.
            BigDecimal tripTotal = nativeDecimal(
                    "SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE trip_id = ?1", tripId);
            BigDecimal owedTotal = actual.stream()
                    .map(MemberBalanceDTO::getTotalOwed)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            check(report, "Q4", "trip " + tripId + ": everything owed equals the trip's spend",
                    tripTotal.compareTo(owedTotal) == 0);
        }
    }

    // ==================================================================
    // Query 5: category spending across a user's trips
    // ==================================================================

    private void checkQuery5(List<String> report, List<Long> userIds) {
        for (Long userId : userIds) {
            List<CategorySpendingDTO> actual = reportService.getCategorySpendingForUser(userId, userId);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT e.category, SUM(s.amount_owed), COUNT(DISTINCT e.trip_id)"
                                    + " FROM expense_splits s"
                                    + "   JOIN expenses e ON e.expense_id = s.expense_id"
                                    + "   JOIN trips t ON t.trip_id = e.trip_id"
                                    + "   JOIN trip_members tm ON tm.trip_id = t.trip_id"
                                    + " WHERE s.user_id = ?1 AND tm.user_id = ?1"
                                    + " GROUP BY e.category"
                                    + " ORDER BY SUM(s.amount_owed) DESC")
                    .setParameter(1, userId)
                    .getResultList();

            Map<String, Object[]> expected = new HashMap<>();
            for (Object[] row : rows) {
                expected.put(row[0] == null ? null : row[0].toString(), row);
            }

            check(report, "Q5", "user " + userId + ": the same categories as SQL",
                    sameValues(new ArrayList<>(expected.keySet()),
                            actual.stream().map(CategorySpendingDTO::getCategory).toList()));

            boolean totalsMatch = true;
            boolean countsMatch = true;
            for (CategorySpendingDTO dto : actual) {
                Object[] row = expected.get(dto.getCategory());
                if (row == null) {
                    totalsMatch = countsMatch = false;
                    continue;
                }
                if (decimal(row[1]).compareTo(dto.getTotalSpent()) != 0) {
                    totalsMatch = false;
                }
                if (((Number) row[2]).longValue() != dto.getTripCount()) {
                    countsMatch = false;
                }
            }
            check(report, "Q5", "user " + userId + ": totals match SQL", totalsMatch);
            check(report, "Q5", "user " + userId + ": distinct trip counts match SQL", countsMatch);

            check(report, "Q5", "user " + userId + ": ordered by total, largest first",
                    sortedBy(actual, CategorySpendingDTO::getTotalSpent, true));

            // The categories must account for everything the user owes, which
            // cross-checks the grouping against a figure the query does not
            // compute. A join that duplicated rows would show up here.
            BigDecimal grouped = actual.stream()
                    .map(CategorySpendingDTO::getTotalSpent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalOwed = reportService.getTotalOwedByUser(userId, userId);
            check(report, "Q5", "user " + userId + ": categories sum to everything they owe",
                    grouped.compareTo(totalOwed) == 0);
        }
    }

    // ==================================================================
    // Cross-query invariants
    // ==================================================================

    private void checkInvariants(List<String> report, List<Long> tripIds) {
        BigDecimal allTrips = tripIds.stream()
                .map(id -> reportService.getTotalSpentForTrip(id, memberOf(id)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal everyExpense = nativeDecimal(
                "SELECT COALESCE(SUM(amount), 0) FROM expenses", null);

        check(report, "invariant", "per-trip totals sum to the total of every expense",
                allTrips.compareTo(everyExpense) == 0);

        // Splits must reconcile with amounts across the whole database, not just
        // in the seed rows the earlier phases checked.
        BigDecimal splitTotal = nativeDecimal(
                "SELECT COALESCE(SUM(amount_owed), 0) FROM expense_splits", null);
        check(report, "invariant", "every split in the database reconciles with its expense",
                splitTotal.compareTo(everyExpense) == 0);

        long unbalanced = nativeLong(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT e.expense_id FROM expenses e"
                        + "  LEFT JOIN expense_splits s ON s.expense_id = e.expense_id"
                        + "  GROUP BY e.expense_id, e.amount"
                        + "  HAVING COALESCE(SUM(s.amount_owed), 0) <> e.amount) bad", null);
        check(report, "invariant", "no expense in the database is out of balance", unbalanced == 0);

        // Q5 for a user must account for exactly what Q4 says they owe, summed
        // over their trips. Two queries written completely differently should
        // not be able to disagree.
        boolean consistent = true;
        for (Long userId : nativeIds("SELECT user_id FROM users ORDER BY user_id")) {
            BigDecimal viaCategories = reportService.getCategorySpendingForUser(userId, userId).stream()
                    .map(CategorySpendingDTO::getTotalSpent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal viaBalances = BigDecimal.ZERO;
            for (Long tripId : tripIds) {
                for (MemberBalanceDTO balance : reportService.getMemberBalances(tripId, memberOf(tripId))) {
                    if (balance.getUserId().equals(userId)) {
                        viaBalances = viaBalances.add(balance.getTotalOwed());
                    }
                }
            }
            if (viaCategories.compareTo(viaBalances) != 0) {
                consistent = false;
            }
        }
        check(report, "invariant", "query 5 and query 4 agree on what every user owes", consistent);

        // The seeded sample data must still be intact after all of this.
        check(report, "invariant", "the sample data is unchanged",
                nativeLong("SELECT COUNT(*) FROM trips", null) == 3
                        && nativeLong("SELECT COUNT(*) FROM expenses", null) == 25
                        && nativeLong("SELECT COUNT(*) FROM expense_splits", null) == 100);
    }

    // ==================================================================
    // Native SQL helpers. These deliberately bypass all entity mapping.
    // ==================================================================

    @SuppressWarnings("unchecked")
    private List<Long> nativeIds(String sql) {
        return nativeIds(sql, null);
    }

    @SuppressWarnings("unchecked")
    private List<Long> nativeIds(String sql, Object parameter) {
        var query = em.createNativeQuery(sql);
        if (parameter != null) {
            query.setParameter(1, parameter);
        }
        List<Long> ids = new ArrayList<>();
        for (Object value : query.getResultList()) {
            ids.add(((Number) value).longValue());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<String> nativeStrings(String sql, Object parameter) {
        var query = em.createNativeQuery(sql);
        if (parameter != null) {
            query.setParameter(1, parameter);
        }
        List<String> values = new ArrayList<>();
        for (Object value : query.getResultList()) {
            values.add(value == null ? null : value.toString());
        }
        return values;
    }

    private BigDecimal nativeDecimal(String sql, Object parameter) {
        Object value = nativeScalar(sql, parameter);
        return value == null ? BigDecimal.ZERO : decimal(value);
    }

    private long nativeLong(String sql, Object parameter) {
        Object value = nativeScalar(sql, parameter);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private Object nativeScalar(String sql, Object parameter) {
        var query = em.createNativeQuery(sql);
        if (parameter != null) {
            query.setParameter(1, parameter);
        }
        return query.getSingleResult();
    }

    private long countSplits(Long expenseId) {
        return nativeLong("SELECT COUNT(*) FROM expense_splits WHERE expense_id = ?1", expenseId);
    }

    private Long pokharaTripId() {
        List<Long> ids = nativeIds(
                "SELECT trip_id FROM trips WHERE trip_name = 'Pokhara Weekend'", null);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ==================================================================
    // Comparison helpers
    // ==================================================================

    /**
     * Compares two id collections as sets.
     *
     * <p>Order is checked separately, as a property of the sequence, because a
     * direct list comparison would be flaky whenever two rows share a sort key:
     * SQL does not define their relative order, so either arrangement is
     * legitimate and the check would fail at random.
     */
    private boolean sameIds(List<Long> expected, List<Long> actual) {
        return new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(actual))
                && expected.size() == actual.size();
    }

    private boolean sameValues(List<String> expected, List<String> actual) {
        return new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(actual))
                && expected.size() == actual.size();
    }

    /**
     * Asserts a list is sorted by the given key, treating nulls as smallest.
     *
     * <p>Checking order as a property of the sequence, rather than comparing
     * against a SQL-ordered list, keeps the check stable when two rows share a
     * sort key: SQL does not define their relative order, so either arrangement
     * is legitimate.
     */
    private <T, K extends Comparable<K>> boolean sortedBy(List<T> items,
                                                          java.util.function.Function<T, K> key,
                                                          boolean descending) {
        for (int i = 1; i < items.size(); i++) {
            K previous = key.apply(items.get(i - 1));
            K current = key.apply(items.get(i));
            if (previous == null) {
                continue;
            }
            if (current == null) {
                return false;
            }
            int comparison = previous.compareTo(current);
            if (descending ? comparison < 0 : comparison > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * A tuple compared element by element, nulls first.
     *
     * <p>Not a joined string: as text, "11" sorts before "9", so a composite
     * string key reports a correctly ordered list of ids as unordered as soon
     * as any id passes nine.
     */
    private static final class CompositeKey implements Comparable<CompositeKey> {

        private final Object[] parts;

        CompositeKey(Object... parts) {
            this.parts = parts;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public int compareTo(CompositeKey other) {
            for (int i = 0; i < parts.length; i++) {
                Comparable left = (Comparable) parts[i];
                Comparable right = (Comparable) other.parts[i];
                if (left == null && right == null) {
                    continue;
                }
                if (left == null) {
                    return -1;
                }
                if (right == null) {
                    return 1;
                }
                int comparison = left.compareTo(right);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        return new BigDecimal(value.toString());
    }

    private void check(List<String> report, String tag, String what, boolean ok) {
        report.add((ok ? "PASS" : "FAIL") + "  [" + tag + "] " + what);
    }

    /**
     * Any member of a trip, to act as the caller for a guarded report.
     *
     * <p>The reporting methods now insist on knowing who is asking, so a check
     * has to supply someone who is actually on the trip. Taken from the
     * database rather than assumed, so the checks exercise the same path a real
     * caller takes instead of bypassing the guard.
     *
     * @return a member's user id, or null when the trip has nobody on it
     */
    private Long memberOf(Long tripId) {
        @SuppressWarnings("unchecked")
        List<Number> ids = em.createNativeQuery(
                        "SELECT user_id FROM trip_members WHERE trip_id = ?1")
                .setParameter(1, tripId)
                .setMaxResults(1)
                .getResultList();
        return ids.isEmpty() ? null : ids.get(0).longValue();
    }
}
