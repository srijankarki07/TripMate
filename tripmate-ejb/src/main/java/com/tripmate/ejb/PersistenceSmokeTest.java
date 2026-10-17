package com.tripmate.ejb;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.tripmate.entity.Expense;
import com.tripmate.entity.ExpenseSplit;
import com.tripmate.entity.ItineraryItem;
import com.tripmate.entity.Settlement;
import com.tripmate.entity.Trip;
import com.tripmate.entity.TripMember;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.TripStatus;
import com.tripmate.entity.User;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Phase 2 scaffolding: proves the seven entity mappings round-trip against
 * PostgreSQL through the real JTA persistence unit.
 *
 * <p>This is <strong>not</strong> domain code and is deleted in Phase 4, when
 * the five real session beans arrive. Its job is to fail loudly if a
 * {@code @Column}, a relationship or a key generator disagrees with
 * {@code database/schema.sql} — errors that would otherwise surface much later
 * as a confusing runtime failure inside a business method.
 *
 * <h2>Why every check runs after flush() and clear()</h2>
 *
 * {@code flush()} pushes the SQL to the database. {@code clear()} then throws
 * the persistence context away, so the {@code find()} and the JPQL counts that
 * follow cannot be answered from EclipseLink's first-level cache. Without the
 * clear, a read-back would prove only that the object graph is in memory and
 * would happily pass against a mistyped column name.
 *
 * <h2>Why constraint violations are not tested here</h2>
 *
 * A failed flush marks the container-managed transaction rollback-only, so the
 * first deliberate violation would poison every statement after it. The
 * database constraints are verified by the fifteen negative cases in
 * {@code docs/phase-1-database.md}, and the business rules that need the EJB
 * tier are verified in Phase 9. This bean tests one thing: that the mappings
 * round-trip.
 *
 * <p>It runs in a single transaction and removes everything it created, so the
 * seeded data is untouched. If it throws, the transaction rolls back and again
 * nothing is left behind.
 */
@Stateless
public class PersistenceSmokeTest {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    private static final BigDecimal DINNER_TOTAL = new BigDecimal("4800.00");
    private static final BigDecimal DINNER_SHARE = new BigDecimal("2400.00");
    private static final BigDecimal SETTLEMENT_AMOUNT = new BigDecimal("1200.00");

    /**
     * Exercises create, read, update and delete across all seven tables.
     *
     * @return one line per check, each prefixed {@code PASS} or {@code FAIL},
     *         ending with a summary line
     */
    public List<String> runRoundTrip() {
        List<String> report = new ArrayList<>();

        // Unique per run, so a row left over from an earlier failed run can
        // never be mistaken for this run's data.
        String tag = UUID.randomUUID().toString().substring(0, 8);

        // ==============================================================
        // users — create, read back, update, re-read
        // ==============================================================
        User alice = new User();
        alice.setFullName("Smoke Alice");
        alice.setEmail("smoke-a-" + tag + "@tripmate.test");
        alice.setPasswordHash("not-a-real-hash");
        alice.setPhone("+977-9800000000");
        em.persist(alice);

        User bob = new User();
        bob.setFullName("Smoke Bob");
        bob.setEmail("smoke-b-" + tag + "@tripmate.test");
        bob.setPasswordHash("not-a-real-hash");
        em.persist(bob);

        em.flush();
        Long aliceId = alice.getUserId();
        Long bobId = bob.getUserId();
        em.clear();

        check(report, "users", "primary key generated on persist",
                aliceId != null && bobId != null);

        // The concrete check on the allocationSize = 1 decision. EclipseLink
        // defaults to 50, which against a sequence that increments by 1 would
        // hand the second insert the first insert's id; this assertion fails
        // loudly if that default is ever allowed back in.
        check(report, "users", "two consecutive inserts get distinct ids",
                aliceId != null && bobId != null && !aliceId.equals(bobId));

        User readAlice = em.find(User.class, aliceId);
        check(report, "users", "read after clear() returns the row from the database",
                readAlice != null && alice.getEmail().equals(readAlice.getEmail()));
        check(report, "users", "created_at populated by @PrePersist",
                readAlice != null && readAlice.getCreatedAt() != null);

        readAlice.setFullName("Smoke Alice Renamed");
        em.flush();
        em.clear();

        User updatedAlice = em.find(User.class, aliceId);
        check(report, "users", "update is persisted",
                updatedAlice != null && "Smoke Alice Renamed".equals(updatedAlice.getFullName()));

        // ==============================================================
        // trips
        // ==============================================================
        Trip trip = new Trip();
        trip.setTripName("Smoke Test Trip " + tag);
        trip.setDestination("Nowhere");
        trip.setStartDate(LocalDate.of(2026, 12, 1));
        trip.setEndDate(LocalDate.of(2026, 12, 5));
        trip.setDescription("Created by PersistenceSmokeTest.");
        trip.setStatus(TripStatus.PLANNING);
        trip.setCreatedBy(updatedAlice);
        em.persist(trip);

        em.flush();
        Long tripId = trip.getTripId();
        String tripName = trip.getTripName();
        em.clear();

        Trip readTrip = em.find(Trip.class, tripId);
        check(report, "trips", "read after clear() returns the row",
                readTrip != null && tripName.equals(readTrip.getTripName()));
        check(report, "trips", "status enum round-trips as a constant",
                readTrip != null && readTrip.getStatus() == TripStatus.PLANNING);
        check(report, "trips", "LocalDate round-trips",
                readTrip != null && LocalDate.of(2026, 12, 1).equals(readTrip.getStartDate()));
        check(report, "trips", "many-to-one to users resolves",
                readTrip != null && readTrip.getCreatedBy() != null
                        && aliceId.equals(readTrip.getCreatedBy().getUserId()));

        readTrip.setStatus(TripStatus.ONGOING);
        em.flush();
        em.clear();

        Trip reReadTrip = em.find(Trip.class, tripId);
        check(report, "trips", "enum update is persisted",
                reReadTrip != null && reReadTrip.getStatus() == TripStatus.ONGOING);

        // ==============================================================
        // trips.description is a TEXT column, not a VARCHAR
        // ==============================================================
        String longText = "x".repeat(1200);
        reReadTrip.setDescription(longText);
        em.flush();
        em.clear();
        Trip longTextTrip = em.find(Trip.class, tripId);
        check(report, "trips", "TEXT column holds content beyond VARCHAR limits",
                longTextTrip != null && longText.equals(longTextTrip.getDescription()));

        // ==============================================================
        // trip_members
        // ==============================================================
        // getReference gives a managed handle for the foreign key without
        // issuing a SELECT; the id is all the insert needs.
        Trip tripRef = em.getReference(Trip.class, tripId);

        TripMember membershipA = new TripMember();
        membershipA.setTrip(tripRef);
        membershipA.setUser(em.getReference(User.class, aliceId));
        membershipA.setRole(TripRole.ADMIN);
        em.persist(membershipA);

        TripMember membershipB = new TripMember();
        membershipB.setTrip(tripRef);
        membershipB.setUser(em.getReference(User.class, bobId));
        membershipB.setRole(TripRole.MEMBER);
        em.persist(membershipB);

        em.flush();
        Long memberIdA = membershipA.getMemberId();
        em.clear();

        TripMember readMember = em.find(TripMember.class, memberIdA);
        check(report, "trip_members", "read after clear() returns the row",
                readMember != null && readMember.getRole() == TripRole.ADMIN);
        check(report, "trip_members", "both many-to-one associations resolve",
                readMember != null && readMember.getTrip() != null && readMember.getUser() != null);
        check(report, "trip_members", "isAdmin() reflects the stored role",
                readMember != null && readMember.isAdmin());
        check(report, "trip_members", "joined_at populated by @PrePersist",
                readMember != null && readMember.getJoinedAt() != null);

        // ==============================================================
        // itinerary_items
        // ==============================================================
        ItineraryItem item = new ItineraryItem();
        item.setTrip(em.getReference(Trip.class, tripId));
        item.setTitle("Smoke Test Breakfast");
        item.setDescription("Created by PersistenceSmokeTest.");
        item.setItemDate(LocalDate.of(2026, 12, 2));
        item.setStartTime(LocalTime.of(8, 30));
        item.setEndTime(LocalTime.of(9, 15));
        item.setLocation("Somewhere");
        item.setCreatedBy(em.getReference(User.class, aliceId));
        em.persist(item);

        em.flush();
        Long itemId = item.getItemId();
        em.clear();

        ItineraryItem readItem = em.find(ItineraryItem.class, itemId);
        check(report, "itinerary_items", "read after clear() returns the row",
                readItem != null && "Smoke Test Breakfast".equals(readItem.getTitle()));
        check(report, "itinerary_items", "LocalTime round-trips as a time, not a timestamp",
                readItem != null && LocalTime.of(8, 30).equals(readItem.getStartTime()));
        check(report, "itinerary_items", "author many-to-one resolves",
                readItem != null && readItem.getCreatedBy() != null);

        // ==============================================================
        // expenses + expense_splits — the cascade relationship
        // ==============================================================
        Expense dinner = new Expense();
        dinner.setTrip(em.getReference(Trip.class, tripId));
        dinner.setPaidBy(em.getReference(User.class, aliceId));
        dinner.setDescription("Smoke Test Dinner");
        dinner.setAmount(DINNER_TOTAL);
        dinner.setCategory("FOOD");
        dinner.setExpenseDate(LocalDate.of(2026, 12, 2));

        // Added through the helper so both sides of the association are set.
        // The splits are never persisted directly: CascadeType.ALL on
        // Expense.splits must carry them.
        ExpenseSplit splitA = new ExpenseSplit();
        splitA.setUser(em.getReference(User.class, aliceId));
        splitA.setAmountOwed(DINNER_SHARE);
        dinner.addSplit(splitA);

        ExpenseSplit splitB = new ExpenseSplit();
        splitB.setUser(em.getReference(User.class, bobId));
        splitB.setAmountOwed(DINNER_SHARE);
        dinner.addSplit(splitB);

        em.persist(dinner);
        em.flush();
        Long expenseId = dinner.getExpenseId();
        Long splitIdA = splitA.getSplitId();
        Long splitIdB = splitB.getSplitId();
        em.clear();

        check(report, "expense_splits", "CascadeType.ALL persisted both splits "
                        + "without persisting them directly",
                splitIdA != null && splitIdB != null && !splitIdA.equals(splitIdB));

        Expense readExpense = em.find(Expense.class, expenseId);
        check(report, "expenses", "read after clear() returns the row",
                readExpense != null && DINNER_TOTAL.compareTo(readExpense.getAmount()) == 0);
        check(report, "expenses", "DECIMAL(12,2) keeps paisa precision",
                readExpense != null && readExpense.getAmount().scale() == 2);

        // Counted with JPQL so the answer comes from the database rather than
        // from the in-memory collection — that is what proves the cascade
        // actually reached SQL.
        long splitCount = em.createQuery(
                        "SELECT COUNT(s) FROM ExpenseSplit s WHERE s.expense.expenseId = :id",
                        Long.class)
                .setParameter("id", expenseId)
                .getSingleResult();
        check(report, "expense_splits", "both split rows exist in the database", splitCount == 2);

        ExpenseSplit readSplit = em.find(ExpenseSplit.class, splitIdA);
        check(report, "expense_splits", "read after clear() returns the row",
                readSplit != null && DINNER_SHARE.compareTo(readSplit.getAmountOwed()) == 0);
        check(report, "expense_splits", "is_settled defaults to false", readSplit != null && !readSplit.isSettled());

        // Two levels of navigation in one path: split -> expense -> trip.
        Long navigatedTripId = em.createQuery(
                        "SELECT s.expense.trip.tripId FROM ExpenseSplit s WHERE s.splitId = :id",
                        Long.class)
                .setParameter("id", splitIdA)
                .getSingleResult();
        check(report, "expense_splits", "two-level path s.expense.trip.tripId resolves",
                tripId.equals(navigatedTripId));

        // ==============================================================
        // settlements — two many-to-one associations to the same entity
        // ==============================================================
        Settlement settlement = new Settlement();
        settlement.setTrip(em.getReference(Trip.class, tripId));
        settlement.setPayer(em.getReference(User.class, bobId));
        settlement.setReceiver(em.getReference(User.class, aliceId));
        settlement.setAmount(SETTLEMENT_AMOUNT);
        settlement.setNote("Smoke test settlement");
        em.persist(settlement);
        em.flush();
        Long settlementId = settlement.getSettlementId();
        em.clear();

        Settlement readSettlement = em.find(Settlement.class, settlementId);
        check(report, "settlements", "read after clear() returns the row",
                readSettlement != null
                        && SETTLEMENT_AMOUNT.compareTo(readSettlement.getAmount()) == 0);
        check(report, "settlements", "both distinct user associations resolve",
                readSettlement != null
                        && readSettlement.getPayer() != null
                        && readSettlement.getReceiver() != null
                        && !readSettlement.getPayer().getUserId()
                                .equals(readSettlement.getReceiver().getUserId()));

        // ==============================================================
        // The seeded data must still be intact
        // ==============================================================
        long seededTrips = em.createQuery(
                        "SELECT COUNT(t) FROM Trip t WHERE t.tripName IN "
                                + "('Pokhara Weekend','Everest Base Camp Trek','Chitwan Jungle Safari')",
                        Long.class)
                .getSingleResult();
        check(report, "seed data", "the three seeded trips are untouched", seededTrips == 3);

        // ==============================================================
        // Deleting the trip removes every trip-owned row via ON DELETE CASCADE
        // ==============================================================
        em.remove(em.find(Trip.class, tripId));
        em.flush();
        em.clear();

        check(report, "cascade", "memberships removed with the trip",
                countForTrip("SELECT COUNT(m) FROM TripMember m WHERE m.trip.tripId = :id", tripId) == 0);
        check(report, "cascade", "itinerary items removed with the trip",
                countForTrip("SELECT COUNT(i) FROM ItineraryItem i WHERE i.trip.tripId = :id", tripId) == 0);
        check(report, "cascade", "expenses removed with the trip",
                countForTrip("SELECT COUNT(e) FROM Expense e WHERE e.trip.tripId = :id", tripId) == 0);
        check(report, "cascade", "settlements removed with the trip",
                countForTrip("SELECT COUNT(s) FROM Settlement s WHERE s.trip.tripId = :id", tripId) == 0);
        // Reached by id, because the expense they hung from is itself gone.
        check(report, "cascade", "splits removed with the trip",
                em.createQuery(
                                "SELECT COUNT(s) FROM ExpenseSplit s WHERE s.splitId IN (:a, :b)",
                                Long.class)
                        .setParameter("a", splitIdA)
                        .setParameter("b", splitIdB)
                        .getSingleResult() == 0);

        // ==============================================================
        // Users must SURVIVE the trip deletion: the trip references them.
        // Then remove them so the run leaves no residue.
        // ==============================================================
        check(report, "referential actions", "users survive deletion of their trip",
                em.find(User.class, aliceId) != null && em.find(User.class, bobId) != null);

        em.remove(em.find(User.class, aliceId));
        em.remove(em.find(User.class, bobId));
        em.flush();
        em.clear();

        check(report, "cleanup", "test users removed; no residue left behind",
                em.find(User.class, aliceId) == null && em.find(User.class, bobId) == null);

        long failures = report.stream().filter(line -> line.startsWith("FAIL")).count();
        report.add(failures == 0
                ? "SUMMARY: all " + report.size() + " checks passed"
                : "SUMMARY: " + failures + " of " + report.size() + " checks FAILED");

        return report;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long countForTrip(String jpql, Long tripId) {
        return em.createQuery(jpql, Long.class).setParameter("id", tripId).getSingleResult();
    }

    private void check(List<String> report, String entity, String what, boolean ok) {
        report.add((ok ? "PASS" : "FAIL") + "  [" + entity + "] " + what);
    }
}
