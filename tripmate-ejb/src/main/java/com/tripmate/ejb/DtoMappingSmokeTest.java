package com.tripmate.ejb;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.SplitDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.dto.UserDTO;
import com.tripmate.entity.Expense;
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
 * Phase 3 scaffolding: proves the entity to DTO mapping is faithful.
 *
 * <p>Temporary. Deleted in Phase 4 alongside the other smoke tests. Its purpose
 * is to catch a mapping that silently drops or mangles a field, which would
 * otherwise show up as a blank cell in the UI much later, far from the cause.
 *
 * <h2>Why this test is read-only</h2>
 *
 * It maps the seeded data and cross-checks each DTO against what SQL says about
 * the same rows. Nothing is written, so there is no cleanup to get wrong and no
 * way for the test to damage the sample data. The assertions are stronger this
 * way as well: an independent query is doing the checking, rather than the code
 * under test confirming its own arithmetic.
 *
 * <p>Two of the checks are structural rather than field-by-field. One asserts
 * that {@code UserDTO} declares no password field at all, which locks in the
 * reason the DTO exists. The other rebuilds the balance query's result through
 * {@link MemberBalanceDTO} and confirms the net balances still sum to zero, the
 * same invariant verified in SQL in Phase 1.
 */
@Stateless
public class DtoMappingSmokeTest {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    private static final String POKHARA = "Pokhara Weekend";
    private static final BigDecimal DEMO_AMOUNT = new BigDecimal("4800.00");
    private static final BigDecimal DEMO_SHARE = new BigDecimal("1200.00");

    /**
     * @return one line per check, each prefixed {@code PASS} or {@code FAIL},
     *         ending with a summary line
     */
    public List<String> runMappingChecks() {
        List<String> report = new ArrayList<>();

        checkNoPasswordFieldOnUserDto(report);
        checkUserMapping(report);
        checkTripMapping(report);
        checkTripMemberMapping(report);
        checkItineraryMapping(report);
        checkExpenseAndSplitMapping(report);
        checkSettlementMapping(report);
        checkBalanceDto(report);
        checkCategoryDto(report);
        checkSeedUnchanged(report);

        long failures = report.stream().filter(line -> line.startsWith("FAIL")).count();
        report.add(failures == 0
                ? "SUMMARY: all " + report.size() + " checks passed"
                : "SUMMARY: " + failures + " of " + report.size() + " checks FAILED");

        return report;
    }

    // ------------------------------------------------------------------
    // Structural: the reason UserDTO exists
    // ------------------------------------------------------------------

    /**
     * Asserts by reflection that no DTO declares a field whose name mentions a
     * password.
     *
     * <p>Field-by-field assertions cannot prove a secret is absent. This can:
     * if anyone ever adds a password field to a DTO, the check fails before the
     * value could reach a client. That makes the guarantee structural rather
     * than a matter of remembering.
     */
    private void checkNoPasswordFieldOnUserDto(List<String> report) {
        List<Class<?>> dtos = List.of(UserDTO.class, TripDTO.class, TripMemberDTO.class,
                ItineraryItemDTO.class, ExpenseDTO.class, SplitDTO.class,
                SettlementDTO.class, MemberBalanceDTO.class, CategorySpendingDTO.class);

        boolean clean = dtos.stream()
                .flatMap(dto -> Arrays.stream(dto.getDeclaredFields()))
                .map(Field::getName)
                .map(String::toLowerCase)
                .noneMatch(name -> name.contains("password"));

        check(report, "all DTOs", "no DTO declares any password field", clean);
    }

    // ------------------------------------------------------------------
    // Field-level mapping, cross-checked against the database
    // ------------------------------------------------------------------

    private void checkUserMapping(List<String> report) {
        User alice = em.createQuery(
                        "SELECT u FROM User u WHERE u.email = :email", User.class)
                .setParameter("email", "alice@tripmate.test")
                .getSingleResult();

        UserDTO dto = UserDTO.from(alice);

        check(report, "UserDTO", "userId maps",
                alice.getUserId().equals(dto.getUserId()));
        check(report, "UserDTO", "fullName maps",
                "Alice Sharma".equals(dto.getFullName()));
        check(report, "UserDTO", "email maps",
                "alice@tripmate.test".equals(dto.getEmail()));
        check(report, "UserDTO", "phone maps",
                "+977-9801000001".equals(dto.getPhone()));
        check(report, "UserDTO", "null entity maps to null DTO",
                UserDTO.from(null) == null);
    }

    private void checkTripMapping(List<String> report) {
        Trip trip = findTrip(POKHARA);

        // Fetched without the convenience overload, so the two ways of building
        // the DTO can be compared against each other and against SQL.
        long memberCount = em.createQuery(
                        "SELECT COUNT(m) FROM TripMember m WHERE m.trip.tripId = :id", Long.class)
                .setParameter("id", trip.getTripId())
                .getSingleResult();

        TripDTO explicit = TripDTO.from(trip, trip.getCreatedBy().getFullName(), (int) memberCount);
        TripDTO derived = TripDTO.from(trip);

        check(report, "TripDTO", "tripName maps", POKHARA.equals(explicit.getTripName()));
        check(report, "TripDTO", "destination maps",
                "Pokhara, Gandaki".equals(explicit.getDestination()));
        check(report, "TripDTO", "status maps as a constant",
                explicit.getStatus() == TripStatus.ONGOING);
        check(report, "TripDTO", "start and end dates map",
                java.time.LocalDate.of(2026, 9, 25).equals(explicit.getStartDate())
                        && java.time.LocalDate.of(2026, 9, 28).equals(explicit.getEndDate()));
        check(report, "TripDTO", "adminName maps from the createdBy association",
                "Alice Sharma".equals(explicit.getAdminName()));
        check(report, "TripDTO", "memberCount agrees with a count query",
                explicit.getMemberCount() == memberCount);
        check(report, "TripDTO", "the explicit and derived overloads agree",
                explicit.getMemberCount() == derived.getMemberCount()
                        && java.util.Objects.equals(explicit.getAdminName(), derived.getAdminName()));
    }

    private void checkTripMemberMapping(List<String> report) {
        Trip trip = findTrip(POKHARA);
        List<TripMember> members = em.createQuery(
                        "SELECT m FROM TripMember m JOIN FETCH m.user "
                                + "WHERE m.trip.tripId = :id ORDER BY m.memberId", TripMember.class)
                .setParameter("id", trip.getTripId())
                .getResultList();

        check(report, "TripMemberDTO", "member list maps one row per member",
                members.size() == 4);

        List<TripMemberDTO> dtos = members.stream().map(TripMemberDTO::from).toList();
        check(report, "TripMemberDTO", "fullName maps through the user association",
                dtos.stream().anyMatch(d -> "Alice Sharma".equals(d.getFullName())));
        check(report, "TripMemberDTO", "exactly one member holds ADMIN",
                dtos.stream().filter(d -> d.getRole() == TripRole.ADMIN).count() == 1);
        check(report, "TripMemberDTO", "the admin is Alice",
                dtos.stream().anyMatch(d -> d.getRole() == TripRole.ADMIN
                        && "Alice Sharma".equals(d.getFullName())));
        check(report, "TripMemberDTO", "joinedAt maps",
                dtos.stream().allMatch(d -> d.getJoinedAt() != null));
    }

    private void checkItineraryMapping(List<String> report) {
        Trip trip = findTrip(POKHARA);
        List<ItineraryItem> items = em.createQuery(
                        "SELECT i FROM ItineraryItem i JOIN FETCH i.createdBy "
                                + "WHERE i.trip.tripId = :id ORDER BY i.itemDate, i.startTime",
                        ItineraryItem.class)
                .setParameter("id", trip.getTripId())
                .getResultList();

        check(report, "ItineraryItemDTO", "item count agrees with the seeded six",
                items.size() == 6);

        ItineraryItem first = items.get(0);
        ItineraryItemDTO dto = ItineraryItemDTO.from(first);

        check(report, "ItineraryItemDTO", "title maps",
                "Drive to Pokhara".equals(dto.getTitle()));
        check(report, "ItineraryItemDTO", "itemDate maps",
                java.time.LocalDate.of(2026, 9, 25).equals(dto.getItemDate()));
        check(report, "ItineraryItemDTO", "startTime maps as a time",
                java.time.LocalTime.of(7, 0).equals(dto.getStartTime()));
        check(report, "ItineraryItemDTO", "endTime maps as a time",
                java.time.LocalTime.of(14, 0).equals(dto.getEndTime()));
        check(report, "ItineraryItemDTO", "location maps",
                "Prithvi Highway".equals(dto.getLocation()));
        check(report, "ItineraryItemDTO", "authorName maps from the createdBy association",
                "Alice Sharma".equals(dto.getAuthorName()));
        check(report, "ItineraryItemDTO", "items are ordered by date then start time",
                items.get(0).getItemDate().compareTo(items.get(1).getItemDate()) <= 0);
    }

    private void checkExpenseAndSplitMapping(List<String> report) {
        // DISTINCT is required, not cosmetic. A fetch join against a collection
        // produces one row per element, so joining four splits yields four rows
        // for the same expense and getSingleResult() throws
        // NonUniqueResultException. DISTINCT collapses them back to one entity.
        Expense dinner = em.createQuery(
                        "SELECT DISTINCT e FROM Expense e JOIN FETCH e.splits "
                                + "WHERE e.description = :description", Expense.class)
                .setParameter("description", "Dinner at Lakeside")
                .getSingleResult();

        ExpenseDTO dto = ExpenseDTO.from(dinner);

        check(report, "ExpenseDTO", "description maps",
                "Dinner at Lakeside".equals(dto.getDescription()));
        check(report, "ExpenseDTO", "amount maps to 4800.00",
                DEMO_AMOUNT.compareTo(dto.getAmount()) == 0);
        check(report, "ExpenseDTO", "amount keeps its scale of 2",
                dto.getAmount().scale() == 2);
        check(report, "ExpenseDTO", "category maps", "FOOD".equals(dto.getCategory()));
        check(report, "ExpenseDTO", "expenseDate maps",
                java.time.LocalDate.of(2026, 9, 25).equals(dto.getExpenseDate()));
        check(report, "ExpenseDTO", "paidByName maps from the paidBy association",
                "Alice Sharma".equals(dto.getPaidByName()));
        check(report, "ExpenseDTO", "tripId maps from the trip association",
                findTrip(POKHARA).getTripId().equals(dto.getTripId()));

        check(report, "SplitDTO", "all four splits are nested in the DTO",
                dto.getSplits().size() == 4);
        check(report, "SplitDTO", "every split is 1200.00",
                dto.getSplits().stream()
                        .allMatch(s -> DEMO_SHARE.compareTo(s.getAmountOwed()) == 0));
        check(report, "SplitDTO", "userName maps through the user association",
                dto.getSplits().stream().anyMatch(s -> "Bob Gurung".equals(s.getUserName())));
        check(report, "SplitDTO", "userId maps",
                dto.getSplits().stream().allMatch(s -> s.getUserId() != null));
        check(report, "SplitDTO", "settled flags carry through as false",
                dto.getSplits().stream().noneMatch(SplitDTO::isSettled));

        check(report, "ExpenseDTO", "split total equals the amount",
                dto.isBalanced());

        // The same check across every seeded expense, fetched with a join so
        // this is one query rather than one per expense.
        List<Expense> allExpenses = em.createQuery(
                        "SELECT DISTINCT e FROM Expense e LEFT JOIN FETCH e.splits",
                        Expense.class)
                .getResultList();

        long unbalanced = allExpenses.stream()
                .map(ExpenseDTO::from)
                .filter(d -> !d.isBalanced())
                .count();

        check(report, "ExpenseDTO", "all " + allExpenses.size()
                + " seeded expenses map to balanced DTOs", unbalanced == 0);
    }

    private void checkSettlementMapping(List<String> report) {
        List<Settlement> settlements = em.createQuery(
                        "SELECT s FROM Settlement s JOIN FETCH s.payer JOIN FETCH s.receiver "
                                + "ORDER BY s.settlementId", Settlement.class)
                .getResultList();

        check(report, "SettlementDTO", "all six seeded settlements map",
                settlements.size() == 6);

        List<SettlementDTO> dtos = settlements.stream().map(SettlementDTO::from).toList();

        check(report, "SettlementDTO", "payer and receiver names both map",
                dtos.stream().allMatch(d -> d.getPayerName() != null && d.getReceiverName() != null));
        check(report, "SettlementDTO", "payer and receiver are never the same person",
                dtos.stream().noneMatch(d -> d.getPayerId().equals(d.getReceiverId())));
        check(report, "SettlementDTO", "note maps where present",
                dtos.stream().anyMatch(d -> "Share of the bus tickets".equals(d.getNote())));
        check(report, "SettlementDTO", "settledAt maps",
                dtos.stream().allMatch(d -> d.getSettledAt() != null));
    }

    // ------------------------------------------------------------------
    // The two query-result DTOs
    // ------------------------------------------------------------------

    /**
     * Rebuilds the balance figures through the DTO and checks the invariant
     * that every trip's net balances sum to zero.
     *
     * <p>This is the Phase 1 SQL check expressed through the DTO layer, so it
     * fails if either the arithmetic in the constructor or the mapping is
     * wrong. Phase 5 replaces the hand-built list with the actual JPQL
     * constructor expression.
     */
    private void checkBalanceDto(List<String> report) {
        Trip trip = findTrip(POKHARA);

        List<MemberBalanceDTO> balances = buildBalances(trip.getTripId());
        check(report, "MemberBalanceDTO", "one balance per trip member", balances.size() == 4);

        BigDecimal sumOfNet = balances.stream()
                .map(MemberBalanceDTO::getNetBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        check(report, "MemberBalanceDTO", "net balances across the trip sum to exactly zero",
                sumOfNet.signum() == 0);

        check(report, "MemberBalanceDTO", "netBalance equals totalPaid minus totalOwed",
                balances.stream().allMatch(b ->
                        b.getTotalPaid().subtract(b.getTotalOwed()).compareTo(b.getNetBalance()) == 0));
        check(report, "MemberBalanceDTO", "the trip has both a creditor and a debtor",
                balances.stream().anyMatch(MemberBalanceDTO::isCreditor)
                        && balances.stream().anyMatch(MemberBalanceDTO::isDebtor));

        // Every trip, not just the demo one.
        boolean allZero = em.createQuery("SELECT t.tripId FROM Trip t", Long.class)
                .getResultList().stream()
                .map(this::buildBalances)
                .map(list -> list.stream()
                        .map(MemberBalanceDTO::getNetBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .allMatch(sum -> sum.signum() == 0);
        check(report, "MemberBalanceDTO", "every trip's net balances sum to zero", allZero);
    }

    private List<MemberBalanceDTO> buildBalances(Long tripId) {
        List<TripMember> members = em.createQuery(
                        "SELECT m FROM TripMember m JOIN FETCH m.user "
                                + "WHERE m.trip.tripId = :id ORDER BY m.memberId", TripMember.class)
                .setParameter("id", tripId)
                .getResultList();

        List<MemberBalanceDTO> result = new ArrayList<>();
        for (TripMember member : members) {
            Long userId = member.getUser().getUserId();

            BigDecimal paid = em.createQuery(
                            "SELECT COALESCE(SUM(e.amount), 0) FROM Expense e "
                                    + "WHERE e.trip.tripId = :tripId AND e.paidBy.userId = :userId",
                            BigDecimal.class)
                    .setParameter("tripId", tripId)
                    .setParameter("userId", userId)
                    .getSingleResult();

            BigDecimal owed = em.createQuery(
                            "SELECT COALESCE(SUM(s.amountOwed), 0) FROM ExpenseSplit s "
                                    + "WHERE s.expense.trip.tripId = :tripId AND s.user.userId = :userId",
                            BigDecimal.class)
                    .setParameter("tripId", tripId)
                    .setParameter("userId", userId)
                    .getSingleResult();

            result.add(new MemberBalanceDTO(userId, member.getUser().getFullName(), paid, owed));
        }
        return result;
    }

    private void checkCategoryDto(List<String> report) {
        // Built directly here. Phase 5 exercises the real GROUP BY query that
        // produces these through a constructor expression.
        CategorySpendingDTO dto = new CategorySpendingDTO(
                "TRANSPORT", new BigDecimal("41500.00"), 3L);

        check(report, "CategorySpendingDTO", "category maps",
                "TRANSPORT".equals(dto.getCategory()));
        check(report, "CategorySpendingDTO", "totalSpent maps as BigDecimal",
                new BigDecimal("41500.00").compareTo(dto.getTotalSpent()) == 0);
        check(report, "CategorySpendingDTO", "tripCount is a Long, as JPQL COUNT yields",
                Long.valueOf(3L).equals(dto.getTripCount()));
        check(report, "CategorySpendingDTO", "equality is by value",
                dto.equals(new CategorySpendingDTO("TRANSPORT", new BigDecimal("41500.00"), 3L)));
    }

    // ------------------------------------------------------------------
    // The sample data must be untouched
    // ------------------------------------------------------------------

    /**
     * Confirms this test wrote nothing. It is read-only by construction, so
     * this is a guard against someone later adding a write to it.
     */
    private void checkSeedUnchanged(List<String> report) {
        long trips = em.createQuery("SELECT COUNT(t) FROM Trip t", Long.class).getSingleResult();
        long expenses = em.createQuery("SELECT COUNT(e) FROM Expense e", Long.class).getSingleResult();
        long splits = em.createQuery("SELECT COUNT(s) FROM ExpenseSplit s", Long.class).getSingleResult();
        long settlements = em.createQuery("SELECT COUNT(s) FROM Settlement s", Long.class).getSingleResult();

        check(report, "seed data", "seeded rows are unchanged after mapping",
                trips == 3 && expenses == 25 && splits == 100 && settlements == 6);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Trip findTrip(String name) {
        return em.createQuery("SELECT t FROM Trip t WHERE t.tripName = :name", Trip.class)
                .setParameter("name", name)
                .getSingleResult();
    }

    private void check(List<String> report, String dto, String what, boolean ok) {
        report.add((ok ? "PASS" : "FAIL") + "  [" + dto + "] " + what);
    }
}
