package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.SplitDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.UserDTO;
import com.tripmate.ejb.DtoMappingSmokeTest;
import com.tripmate.ejb.PersistenceSmokeTest;
import com.tripmate.ejb.QueryVerificationSmokeTest;
import com.tripmate.ejb.SessionBeanSmokeTest;
import com.tripmate.entity.TripRole;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.remote.UserServiceRemote;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Runs every check the project has, from the web tier.
 *
 * <h2>Why the rejection cases are driven from here</h2>
 *
 * The three suites below each run inside a single bean transaction, which is
 * what makes them atomic and leaves nothing behind. That also makes them the
 * wrong place to test a deliberate failure: an exception escaping a session
 * bean is a system exception to the container, so it marks the transaction
 * rollback-only and every later statement in the same transaction fails for an
 * unrelated reason.
 *
 * <p>This bean has no transaction of its own, so each call below runs in its
 * own. A rejection rolls back that one call and nothing else, which is both
 * correct and what makes the cases testable at all.
 */
@Named
@RequestScoped
public class DiagnosticsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private PersistenceSmokeTest persistenceTest;

    @EJB
    private DtoMappingSmokeTest dtoMappingTest;

    @EJB
    private SessionBeanSmokeTest sessionBeanTest;

    @EJB
    private QueryVerificationSmokeTest queryVerificationTest;

    @EJB
    private UserServiceRemote userService;

    @EJB
    private TripServiceRemote tripService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private ReportServiceRemote reportService;

    private List<String> report;
    private String error;

    public void runAll() {
        report = new ArrayList<>();
        error = null;

        // Each suite is caught separately so one failing does not hide the
        // other two.
        runSuite("Persistence: entity round-trip", persistenceTest::runRoundTrip);
        runSuite("DTO mapping", dtoMappingTest::runMappingChecks);
        runSuite("Session bean CRUD", sessionBeanTest::runCrudChecks);
        runSuite("The five required queries", queryVerificationTest::runQueryChecks);
        runSuite("Session bean rejections", this::runRejectionChecks);
        runSuite("Balances and settle-up arithmetic", this::runBalanceArithmeticChecks);
    }

    // ------------------------------------------------------------------
    // Suites
    // ------------------------------------------------------------------

    private void runSuite(String title, Supplier<List<String>> suite) {
        report.add("SECTION: " + title);
        try {
            report.addAll(suite.get());
        } catch (RuntimeException ex) {
            report.add("FAIL  [" + title + "] suite did not complete: " + describe(ex));
        }
    }

    /**
     * Checks the arithmetic behind the balances page.
     *
     * <p>That page combines query 4's gross figures with the settlements to get
     * what is still outstanding, then suggests payments from those. It is the
     * only place in the application where a figure the user acts on is computed
     * in the web tier rather than by the database, so it is worth asserting
     * rather than trusting.
     *
     * <p>The checks are properties rather than expected values, so they hold for
     * any data: outstanding must sum to zero, and the suggestions must total
     * exactly that and never ask anyone to pay more than they still owe.
     */
    private List<String> runBalanceArithmeticChecks() {
        List<String> lines = new ArrayList<>();
        UserDTO alice = userService.getUserByEmail("alice@tripmate.test");

        for (TripDTO trip : tripService.getTripsForUser(alice.getUserId())) {
            String label = "trip " + trip.getTripId();
            List<MemberBalanceDTO> balances = reportService.getMemberBalances(trip.getTripId());
            List<SettlementDTO> settlements = expenseService.getSettlementsForTrip(trip.getTripId());
            List<BalancesBean.MemberPosition> positions =
                    BalancesBean.buildPositions(balances, settlements);

            BigDecimal outstandingTotal = positions.stream()
                    .map(BalancesBean.MemberPosition::getOutstanding)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            check(lines, "Balances", label + ": outstanding positions sum to exactly zero",
                    outstandingTotal.signum() == 0);

            // A member who has neither paid nor received a repayment must have
            // the same outstanding figure as their gross net.
            check(lines, "Balances", label + ": an untouched member's outstanding equals their net",
                    positions.stream()
                            .filter(BalancesBean.MemberPosition::isUntouchedBySettlements)
                            .allMatch(p -> p.getOutstanding().compareTo(p.getNet()) == 0));

            List<BalancesBean.Transfer> suggested = BalancesBean.suggestTransfers(positions);

            BigDecimal suggestedTotal = suggested.stream()
                    .map(BalancesBean.Transfer::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal owedToCreditors = positions.stream()
                    .filter(BalancesBean.MemberPosition::isCreditor)
                    .map(BalancesBean.MemberPosition::getOutstanding)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            check(lines, "Settle-up", label + ": suggestions total what is owed to creditors",
                    suggestedTotal.compareTo(owedToCreditors) == 0);

            // The property that matters most: nobody is asked to pay more than
            // they still owe. Reading the gross figures instead of the
            // outstanding ones breaks exactly this.
            boolean nobodyOverpays = true;
            for (BalancesBean.MemberPosition p : positions) {
                BigDecimal asked = suggested.stream()
                        .filter(t -> t.getFrom().equals(p.getFullName()))
                        .map(BalancesBean.Transfer::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (asked.compareTo(p.getOutstandingMagnitude()) > 0) {
                    nobodyOverpays = false;
                }
            }
            check(lines, "Settle-up", label + ": nobody is asked to pay more than they owe",
                    nobodyOverpays);

            check(lines, "Settle-up", label + ": a settled trip needs no payments",
                    !positions.stream().noneMatch(p -> p.isDebtor()) || suggested.isEmpty());
        }

        long failures = lines.stream().filter(l -> l.startsWith("FAIL")).count();
        lines.add(failures == 0
                ? "SUMMARY: all " + lines.size() + " arithmetic checks passed"
                : "SUMMARY: " + failures + " of " + lines.size() + " arithmetic checks FAILED");
        return lines;
    }

    /**
     * Rules that reject input. Nothing is written by any of these, so the
     * sample data is unaffected and no cleanup is needed.
     */
    private List<String> runRejectionChecks() {
        List<String> lines = new ArrayList<>();

        UserDTO alice = userService.getUserByEmail("alice@tripmate.test");
        UserDTO bob = userService.getUserByEmail("bob@tripmate.test");
        TripDTO pokhara = tripService.getTripsForUser(alice.getUserId()).stream()
                .filter(t -> "Pokhara Weekend".equals(t.getTripName()))
                .findFirst()
                .orElseThrow();

        // Alice already administers Pokhara, so adding her again must fail.
        expectRejected(lines, "TripService", "a duplicate membership is rejected",
                () -> tripService.addMember(pokhara.getTripId(), alice.getUserId(), TripRole.MEMBER));

        expectRejected(lines, "TripService", "adding a member to a non-existent trip is rejected",
                () -> tripService.addMember(-1L, bob.getUserId(), TripRole.MEMBER));

        expectRejected(lines, "TripService", "removing a non-member is rejected",
                () -> tripService.removeMember(pokhara.getTripId(), 999999L));

        expectRejected(lines, "UserService", "registering a duplicate email is rejected",
                () -> userService.register(
                        new UserDTO(null, "Imposter", "alice@tripmate.test", null), "whatever"));

        // The flagship rule: splits must reconcile with the amount.
        expectRejected(lines, "ExpenseService", "an expense whose splits do not sum to its amount is rejected",
                () -> expenseService.persistExpense(
                        unbalancedExpense(pokhara.getTripId(), alice.getUserId())));

        expectRejected(lines, "ExpenseService", "a zero amount is rejected",
                () -> {
                    ExpenseDTO zero = unbalancedExpense(pokhara.getTripId(), alice.getUserId());
                    zero.setAmount(BigDecimal.ZERO);
                    expenseService.persistExpense(zero);
                });

        expectRejected(lines, "ExpenseService", "an expense with no splits is rejected",
                () -> {
                    ExpenseDTO none = unbalancedExpense(pokhara.getTripId(), alice.getUserId());
                    none.getSplits().clear();
                    expenseService.persistExpense(none);
                });

        expectRejected(lines, "ExpenseService", "a split for an unknown user is rejected",
                () -> {
                    ExpenseDTO bad = unbalancedExpense(pokhara.getTripId(), alice.getUserId());
                    bad.getSplits().clear();
                    bad.addSplit(new SplitDTO(999999L, null, new BigDecimal("4800.00"), false));
                    expenseService.persistExpense(bad);
                });

        // Business rule ROADMAP section 8.3: a settlement needs two people.
        expectRejected(lines, "ExpenseService", "a settlement paying yourself is rejected",
                () -> {
                    SettlementDTO self = new SettlementDTO();
                    self.setTripId(pokhara.getTripId());
                    self.setPayerId(alice.getUserId());
                    self.setReceiverId(alice.getUserId());
                    self.setAmount(new BigDecimal("100.00"));
                    expenseService.recordSettlement(self);
                });

        expectRejected(lines, "ExpenseService", "a negative settlement is rejected",
                () -> {
                    SettlementDTO negative = new SettlementDTO();
                    negative.setTripId(pokhara.getTripId());
                    negative.setPayerId(bob.getUserId());
                    negative.setReceiverId(alice.getUserId());
                    negative.setAmount(new BigDecimal("-50.00"));
                    expenseService.recordSettlement(negative);
                });

        long failures = lines.stream().filter(line -> line.startsWith("FAIL")).count();
        lines.add(failures == 0
                ? "SUMMARY: all " + lines.size() + " rejections behaved as required"
                : "SUMMARY: " + failures + " of " + lines.size() + " rejections FAILED");

        return lines;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** 4800.00 with splits totalling 4000.00, which must not be accepted. */
    private ExpenseDTO unbalancedExpense(Long tripId, Long paidById) {
        ExpenseDTO expense = new ExpenseDTO();
        expense.setTripId(tripId);
        expense.setPaidById(paidById);
        expense.setDescription("Deliberately unbalanced");
        expense.setAmount(new BigDecimal("4800.00"));
        expense.setCategory("FOOD");
        expense.setExpenseDate(LocalDate.now());
        expense.addSplit(new SplitDTO(paidById, null, new BigDecimal("4000.00"), false));
        return expense;
    }

    /** Records one check as a report line. */
    private void check(List<String> report, String tag, String what, boolean ok) {
        report.add((ok ? "PASS" : "FAIL") + "  [" + tag + "] " + what);
    }

    private void expectRejected(List<String> lines, String bean, String what, Runnable action) {
        try {
            action.run();
            lines.add("FAIL  [" + bean + "] " + what + " (it was accepted)");
        } catch (RuntimeException expected) {
            lines.add("PASS  [" + bean + "] " + what);
        }
    }

    private static String describe(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ------------------------------------------------------------------
    // View state
    // ------------------------------------------------------------------

    public List<String> getReport() {
        return report;
    }

    public String getError() {
        return error;
    }

    public boolean isHasRun() {
        return report != null || error != null;
    }

    public boolean isAllPassed() {
        return report != null && report.stream().noneMatch(line -> line.startsWith("FAIL"));
    }

    public long getPassCount() {
        return report == null ? 0 : report.stream().filter(l -> l.startsWith("PASS")).count();
    }

    public long getFailCount() {
        return report == null ? 0 : report.stream().filter(l -> l.startsWith("FAIL")).count();
    }
}
