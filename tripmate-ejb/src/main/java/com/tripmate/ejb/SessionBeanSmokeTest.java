package com.tripmate.ejb;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.SplitDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.dto.UserDTO;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.TripStatus;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.remote.UserServiceRemote;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

/**
 * Phase 4 scaffolding: drives all five session beans through their remote
 * interfaces and checks what comes back.
 *
 * <p>The point is not the CRUD itself but the wiring. A bean that deploys but
 * whose remote interface cannot be resolved, or whose method returns the wrong
 * shape, fails here rather than three phases later inside a JSF page.
 *
 * <h2>Why only the happy path runs in here</h2>
 *
 * Every method call joins this bean's container-managed transaction. That is
 * what makes the run atomic: if anything throws, the whole thing rolls back and
 * nothing is left behind, so there is no cleanup to get wrong.
 *
 * <p>It also means deliberate failures cannot be tested in here. An
 * {@code IllegalArgumentException} escaping a session bean is a system
 * exception to the container, which marks the transaction rollback-only; the
 * next statement in this method would then fail for a reason unrelated to what
 * was being tested. The rejection cases are therefore driven from the web tier
 * in {@code DiagnosticsBean}, where each call is its own transaction.
 */
@Stateless
public class SessionBeanSmokeTest {

    @EJB
    private UserServiceRemote userService;

    @EJB
    private EmailVerificationService emailVerificationService;

    @EJB
    private TripServiceRemote tripService;

    @EJB
    private ItineraryServiceRemote itineraryService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private ReportServiceRemote reportService;

    private static final BigDecimal EXPENSE_TOTAL = new BigDecimal("4800.00");
    private static final BigDecimal EXPENSE_SHARE = new BigDecimal("2400.00");
    private static final BigDecimal SETTLEMENT_AMOUNT = new BigDecimal("1200.00");

    /**
     * Creates, reads, updates and deletes through all five beans.
     *
     * @return one line per check, prefixed {@code PASS} or {@code FAIL},
     *         ending with a summary
     */
    public List<String> runCrudChecks() {
        List<String> report = new ArrayList<>();
        String tag = UUID.randomUUID().toString().substring(0, 8);

        // ==============================================================
        // UserService
        // ==============================================================
        UserDTO alice = userService.register(
                new UserDTO(null, "Smoke Alice", "sb-a-" + tag + "@tripmate.test", "+977-9800000001"),
                "smoke-password-1");
        UserDTO bob = userService.register(
                new UserDTO(null, "Smoke Bob", "sb-b-" + tag + "@tripmate.test", "+977-9800000002"),
                "smoke-password-2");

        check(report, "UserService", "register assigns an id",
                alice.getUserId() != null && bob.getUserId() != null);
        check(report, "UserService", "register returns the profile fields it was given",
                "Smoke Alice".equals(alice.getFullName())
                        && "+977-9800000001".equals(alice.getPhone()));

        // Exercises PasswordHasher end to end: the hash written by register has
        // to verify against the password that produced it.
        check(report, "UserService", "login succeeds with the registered password",
                userService.login(alice.getEmail(), "smoke-password-1") != null);
        check(report, "UserService", "login fails with the wrong password",
                userService.login(alice.getEmail(), "not-the-password") == null);
        check(report, "UserService", "login fails for an unknown email",
                userService.login("nobody-" + tag + "@tripmate.test", "whatever") == null);

        // ---------------------------------------------------------------
        // Email verification
        //
        // Only the paths that return rather than throw. The refusal cases
        // (resending too soon, running out of attempts) raise, and a raise here
        // would poison the transaction everything else in this method shares.
        // See the class comment.
        // ---------------------------------------------------------------
        check(report, "EmailVerificationService", "registering leaves the address unconfirmed",
                !alice.isEmailVerified() && !bob.isEmailVerified());

        UserDTO byPassword = userService.login(alice.getEmail(), "smoke-password-1");
        check(report, "EmailVerificationService",
                "the credentials still authenticate an unconfirmed account",
                byPassword != null && !byPassword.isEmailVerified());

        check(report, "EmailVerificationService", "registering leaves a code waiting",
                emailVerificationService.latestCodeExpiry(alice.getUserId()) != null);

        // Five digits, so it cannot be the six-digit code whatever that is: a
        // test that guessed would fail once in a million runs, which is worse
        // than not having it.
        check(report, "EmailVerificationService", "a code of the wrong length is refused",
                emailVerificationService.verify(alice.getUserId(), "12345")
                        == EmailVerificationService.Outcome.WRONG_CODE);
        check(report, "EmailVerificationService", "a malformed code costs no attempt",
                emailVerificationService.attemptsRemaining(alice.getUserId())
                        == EmailVerificationService.MAX_ATTEMPTS);

        check(report, "UserService", "getUser reads back",
                alice.getEmail().equals(userService.getUser(alice.getUserId()).getEmail()));
        check(report, "UserService", "getUserByEmail reads back",
                alice.getUserId().equals(userService.getUserByEmail(alice.getEmail()).getUserId()));

        UserDTO edited = new UserDTO();
        edited.setUserId(alice.getUserId());
        edited.setFullName("Smoke Alice Renamed");
        check(report, "UserService", "updateUser persists a name change",
                "Smoke Alice Renamed".equals(userService.updateUser(edited).getFullName()));

        userService.changePassword(alice.getUserId(), "smoke-password-1",
                "smoke-password-1-changed", alice.getUserId());
        check(report, "UserService", "changePassword takes effect",
                userService.login(alice.getEmail(), "smoke-password-1") == null
                        && userService.login(alice.getEmail(), "smoke-password-1-changed") != null);

        check(report, "UserService", "getAllUsers includes the new accounts",
                userService.getAllUsers().size() >= 7);

        // ==============================================================
        // TripService
        // ==============================================================
        TripDTO newTrip = new TripDTO();
        newTrip.setTripName("Smoke Trip " + tag);
        newTrip.setDestination("Somewhere");
        newTrip.setDescription("Created by SessionBeanSmokeTest.");
        newTrip.setStartDate(LocalDate.of(2027, 3, 1));
        newTrip.setEndDate(LocalDate.of(2027, 3, 5));
        newTrip.setStatus(TripStatus.PLANNING);

        TripDTO trip = tripService.createTrip(newTrip, alice.getUserId());
        Long tripId = trip.getTripId();

        check(report, "TripService", "createTrip assigns an id", tripId != null);
        check(report, "TripService", "createTrip returns a member count of one",
                trip.getMemberCount() == 1);
        check(report, "TripService", "createTrip records the creator as admin",
                tripService.getMemberRole(tripId, alice.getUserId()) == TripRole.ADMIN);
        check(report, "TripService", "the description survives the round trip",
                "Created by SessionBeanSmokeTest.".equals(trip.getDescription()));

        TripDTO fetched = tripService.getTrip(tripId);
        check(report, "TripService", "getTrip reads back",
                fetched != null && trip.getTripName().equals(fetched.getTripName()));
        check(report, "TripService", "getTrip reports the administrator's name",
                "Smoke Alice Renamed".equals(fetched.getAdminName()));

        check(report, "TripService", "getTripsForUser finds the trip by membership",
                tripService.getTripsForUser(alice.getUserId()).stream()
                        .anyMatch(t -> tripId.equals(t.getTripId())));
        check(report, "TripService", "getTripsForUser returns nothing for a non-member",
                tripService.getTripsForUser(bob.getUserId()).stream()
                        .noneMatch(t -> tripId.equals(t.getTripId())));

        TripMemberDTO bobMember = tripService.addMember(tripId, bob.getUserId(), TripRole.MEMBER, alice.getUserId());
        check(report, "TripService", "addMember assigns an id", bobMember.getMemberId() != null);
        check(report, "TripService", "addMember records the role",
                bobMember.getRole() == TripRole.MEMBER);
        check(report, "TripService", "isMember agrees",
                tripService.isMember(tripId, bob.getUserId()));
        check(report, "TripService", "getTripMembers returns both members",
                tripService.getTripMembers(tripId).size() == 2);

        TripDTO statusChange = new TripDTO();
        statusChange.setTripId(tripId);
        statusChange.setStatus(TripStatus.ONGOING);
        check(report, "TripService", "updateTrip persists a status change",
                tripService.updateTrip(statusChange, alice.getUserId()).getStatus() == TripStatus.ONGOING);

        // ==============================================================
        // ItineraryService
        // ==============================================================
        ItineraryItemDTO morning = itineraryService.addItem(tripId,
                itinerary("Smoke Breakfast", LocalDate.of(2027, 3, 2), LocalTime.of(8, 0)),
                alice.getUserId());
        itineraryService.addItem(tripId,
                itinerary("Smoke Sunrise", LocalDate.of(2027, 3, 2), LocalTime.of(5, 30)),
                bob.getUserId());

        check(report, "ItineraryService", "addItem assigns an id", morning.getItemId() != null);

        List<ItineraryItemDTO> itinerary = itineraryService.getItineraryForTrip(tripId);
        check(report, "ItineraryService", "getItineraryForTrip returns both items",
                itinerary.size() == 2);
        // Query 1's ordering: 05:30 was added second but must sort first.
        check(report, "ItineraryService", "query 1 orders by date then start time",
                "Smoke Sunrise".equals(itinerary.get(0).getTitle()));
        check(report, "ItineraryService", "the author's name is resolved",
                itinerary.stream().anyMatch(i -> "Smoke Bob".equals(i.getAuthorName())));

        ItineraryItemDTO editedItem = new ItineraryItemDTO();
        editedItem.setItemId(morning.getItemId());
        editedItem.setTitle("Smoke Brunch");
        check(report, "ItineraryService", "updateItem persists a title change",
                "Smoke Brunch".equals(itineraryService.updateItem(editedItem, alice.getUserId()).getTitle()));
        check(report, "ItineraryService", "getItem reads back",
                "Smoke Brunch".equals(itineraryService.getItem(morning.getItemId()).getTitle()));
        check(report, "ItineraryService", "getItemCountForTrip agrees",
                itineraryService.getItemCountForTrip(tripId) == 2);

        // The calendar feed writes this count as each event's SEQUENCE, which is
        // how a calendar application tells an edited event from one it has
        // already read. A count that does not move means an edit that reaches
        // this application and nobody's calendar, and nothing else in the
        // application would look wrong.
        check(report, "ItineraryService", "a new item starts at revision 0",
                morning.getRevision() == 0);
        check(report, "ItineraryService", "an edit counts up once",
                itineraryService.getItem(morning.getItemId()).getRevision() == 1);

        // ==============================================================
        // ExpenseService
        // ==============================================================
        ExpenseDTO expense = expenseOf(tripId, alice.getUserId(), alice.getUserId(), bob.getUserId());
        ExpenseDTO stored = expenseService.persistExpense(expense, alice.getUserId());

        check(report, "ExpenseService", "persistExpense assigns an id", stored.getExpenseId() != null);
        check(report, "ExpenseService", "both splits are written with the expense",
                stored.getSplits().size() == 2);
        check(report, "ExpenseService", "the splits reconcile with the amount",
                stored.isBalanced());
        check(report, "ExpenseService", "the payer's name is resolved",
                "Smoke Alice Renamed".equals(stored.getPaidByName()));

        List<ExpenseDTO> tripExpenses = expenseService.getExpensesForTrip(tripId);
        check(report, "ExpenseService", "query 2 returns the expense once, not once per split",
                tripExpenses.size() == 1);
        check(report, "ExpenseService", "query 2 carries the splits",
                tripExpenses.get(0).getSplits().size() == 2);

        check(report, "ExpenseService", "getExpense reads back",
                EXPENSE_TOTAL.compareTo(expenseService.getExpense(stored.getExpenseId()).getAmount()) == 0);
        check(report, "ExpenseService", "getTotalSpentForTrip sums the trip",
                EXPENSE_TOTAL.compareTo(expenseService.getTotalSpentForTrip(tripId)) == 0);

        SettlementDTO settlement = new SettlementDTO();
        settlement.setTripId(tripId);
        settlement.setPayerId(bob.getUserId());
        settlement.setReceiverId(alice.getUserId());
        settlement.setAmount(SETTLEMENT_AMOUNT);
        settlement.setNote("Smoke settlement");

        SettlementDTO recorded = expenseService.recordSettlement(settlement, alice.getUserId());
        check(report, "ExpenseService", "recordSettlement assigns an id",
                recorded.getSettlementId() != null);
        check(report, "ExpenseService", "both parties' names resolve",
                "Smoke Bob".equals(recorded.getPayerName())
                        && "Smoke Alice Renamed".equals(recorded.getReceiverName()));
        check(report, "ExpenseService", "getSettlementsForTrip returns it",
                expenseService.getSettlementsForTrip(tripId).size() == 1);

        // ==============================================================
        // ReportService
        // ==============================================================
        List<MemberBalanceDTO> balances = reportService.getMemberBalances(tripId, alice.getUserId());

        check(report, "ReportService", "query 4 returns one row per member",
                balances.size() == 2);
        check(report, "ReportService", "query 4 totals what Alice paid",
                balances.stream()
                        .filter(b -> b.getUserId().equals(alice.getUserId()))
                        .allMatch(b -> EXPENSE_TOTAL.compareTo(b.getTotalPaid()) == 0));
        check(report, "ReportService", "query 4 totals what Bob owes",
                balances.stream()
                        .filter(b -> b.getUserId().equals(bob.getUserId()))
                        .allMatch(b -> EXPENSE_SHARE.compareTo(b.getTotalOwed()) == 0));
        check(report, "ReportService", "query 4 net balances sum to zero",
                balances.stream().map(MemberBalanceDTO::getNetBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).signum() == 0);
        check(report, "ReportService", "query 4 still sums to zero after a settlement, "
                        + "because it reports the gross position",
                balances.stream().map(MemberBalanceDTO::getNetBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).signum() == 0
                        && expenseService.getSettlementsForTrip(tripId).size() == 1);

        List<CategorySpendingDTO> categories =
                reportService.getCategorySpendingForUser(alice.getUserId(), alice.getUserId());
        check(report, "ReportService", "query 5 returns the FOOD category",
                categories.stream().anyMatch(c -> "FOOD".equals(c.getCategory())));
        check(report, "ReportService", "query 5 counts distinct trips",
                categories.stream().allMatch(c -> c.getTripCount() != null && c.getTripCount() >= 1));
        check(report, "ReportService", "query 5 orders by total descending",
                isDescending(categories));

        check(report, "ReportService", "getTripCountForUser counts memberships",
                reportService.getTripCountForUser(alice.getUserId(), alice.getUserId()) >= 1);
        check(report, "ReportService", "getTotalPaidByUser covers every trip",
                reportService.getTotalPaidByUser(alice.getUserId(), alice.getUserId()).signum() > 0);
        check(report, "ReportService", "getTotalOwedByUser covers every split",
                reportService.getTotalOwedByUser(alice.getUserId(), alice.getUserId()).signum() > 0);

        // ==============================================================
        // Deletes, in dependency order
        // ==============================================================
        expenseService.deleteSettlement(recorded.getSettlementId(), alice.getUserId());
        check(report, "ExpenseService", "deleteSettlement removes the row",
                expenseService.getSettlementsForTrip(tripId).isEmpty());

        expenseService.deleteExpense(stored.getExpenseId(), alice.getUserId());
        check(report, "ExpenseService", "deleteExpense removes the expense",
                expenseService.getExpense(stored.getExpenseId()) == null);
        check(report, "ExpenseService", "the splits went with the expense",
                expenseService.getExpensesForTrip(tripId).isEmpty());

        itineraryService.deleteItem(morning.getItemId(), alice.getUserId());
        check(report, "ItineraryService", "deleteItem removes the row",
                itineraryService.getItem(morning.getItemId()) == null);

        tripService.removeMember(tripId, bob.getUserId(), alice.getUserId());
        check(report, "TripService", "removeMember removes the membership",
                !tripService.isMember(tripId, bob.getUserId()));

        tripService.deleteTrip(tripId, alice.getUserId());
        check(report, "TripService", "deleteTrip removes the trip",
                tripService.getTrip(tripId) == null);
        check(report, "TripService", "the trip's remaining rows went with it",
                itineraryService.getItemCountForTrip(tripId) == 0
                        && tripService.getTripMembers(tripId).isEmpty());

        userService.deleteUser(bob.getUserId());
        userService.deleteUser(alice.getUserId());
        check(report, "UserService", "deleteUser removes both accounts",
                userService.getUser(alice.getUserId()) == null
                        && userService.getUser(bob.getUserId()) == null);

        long failures = report.stream().filter(line -> line.startsWith("FAIL")).count();
        report.add(failures == 0
                ? "SUMMARY: all " + report.size() + " checks passed"
                : "SUMMARY: " + failures + " of " + report.size() + " checks FAILED");
        return report;
    }

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    private ItineraryItemDTO itinerary(String title, LocalDate date, LocalTime start) {
        ItineraryItemDTO item = new ItineraryItemDTO();
        item.setTitle(title);
        item.setDescription("Created by SessionBeanSmokeTest.");
        item.setItemDate(date);
        item.setStartTime(start);
        item.setEndTime(start.plusHours(1));
        item.setLocation("Somewhere");
        return item;
    }

    /** One 4800.00 expense split evenly between two participants. */
    private ExpenseDTO expenseOf(Long tripId, Long paidById, Long... participants) {
        ExpenseDTO expense = new ExpenseDTO();
        expense.setTripId(tripId);
        expense.setPaidById(paidById);
        expense.setDescription("Smoke Dinner");
        expense.setAmount(EXPENSE_TOTAL);
        expense.setCategory("FOOD");
        expense.setExpenseDate(LocalDate.of(2027, 3, 2));
        for (Long userId : participants) {
            expense.addSplit(new SplitDTO(userId, null, EXPENSE_SHARE, false));
        }
        return expense;
    }

    private boolean isDescending(List<CategorySpendingDTO> categories) {
        for (int i = 1; i < categories.size(); i++) {
            if (categories.get(i - 1).getTotalSpent()
                    .compareTo(categories.get(i).getTotalSpent()) < 0) {
                return false;
            }
        }
        return true;
    }

    private void check(List<String> report, String bean, String what, boolean ok) {
        report.add((ok ? "PASS" : "FAIL") + "  [" + bean + "] " + what);
    }
}
